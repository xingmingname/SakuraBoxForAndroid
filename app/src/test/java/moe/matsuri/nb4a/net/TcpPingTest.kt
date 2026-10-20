package moe.matsuri.nb4a.net

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class TcpPingTest {
    private class Network(val id: String, val socket: Socket = Socket()) : PingNetwork {
        val events = mutableListOf<String>()
        var failure: IOException? = null
        var closed = false
        var lookupFailure: IOException? = null
        override fun checkNetwork() {
            failure?.let { throw it }
            check(!closed)
        }
        override fun lookup(host: String): Array<InetAddress> {
            checkNetwork(); events += "$id:dns"
            lookupFailure?.let { throw it }
            return arrayOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        override fun createSocket(): Socket { checkNetwork(); events += "$id:socket"; return socket }
        override fun close() { closed = true }
    }

    private fun tick(): Long = System.nanoTime() / 1_000_000

    private fun assertPreserved(expected: IOException, actual: IOException) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        // Coroutine stack recovery may copy an exception, retaining the original as its cause.
        assertSame(expected, generateSequence<Throwable>(actual) { it.cause }.take(16).firstOrNull { it === expected })
    }

    @Test fun realLocalTcpConnectionUsesTheChosenDnsAndSocketContext() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val peer = thread { server.accept().close() }
            val network = Network("external-vpn-selected")
            val result = measureTcpPing("synthetic.invalid", server.localPort, 3000, { network }, ::tick)
            peer.join(2000)
            assertFalse(peer.isAlive)
            assertTrue(result >= 0)
            assertEquals(listOf("external-vpn-selected:dns", "external-vpn-selected:socket"), network.events)
            assertTrue(network.closed)
            assertTrue(network.socket.isClosed)
        }
    }

    @Test fun allNetworkPolicyRolesFeedTheSameTcpDnsAndSocketPipeline() = runBlocking {
        val wifi = TestNetworkCandidate("wifi", true, false)
        val vpn = TestNetworkCandidate("external", true, true)
        val own = TestNetworkCandidate("own", true, true, true)
        val states = listOf(
            TestNetworkPolicy.select(wifi, listOf(wifi), false),
            TestNetworkPolicy.select(vpn, listOf(wifi, vpn), false),
            TestNetworkPolicy.select(wifi, listOf(wifi, vpn), false),
            TestNetworkPolicy.select(own, listOf(wifi, own), true),
        )
        for (selected in states) {
            val socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { assertEquals(3000, timeout) }
            }
            val network = Network(selected.network, socket)
            measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick)
            assertEquals(listOf("${selected.network}:dns", "${selected.network}:socket"), network.events)
            assertTrue(network.closed && socket.isClosed)
        }
    }

    @Test fun unavailableNetworkFailsBeforeDnsAndDoesNotTryAnother() = runBlocking {
        val calls = AtomicInteger()
        val expected = IOException("synthetic blocked application route")
        try {
            measureTcpPing("synthetic.invalid", 443, 3000, { calls.incrementAndGet(); throw expected }, ::tick)
            fail()
        } catch (error: IOException) { assertPreserved(expected, error) }
        assertEquals(1, calls.get())
    }

    @Test fun dnsFailureDoesNotOpenSocketOrFallbackToDefaultDns() = runBlocking {
        val expected = UnknownHostException("synthetic DNS rejection")
        val network = Network("pinned").apply { lookupFailure = expected }
        try {
            measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick)
            fail()
        } catch (error: UnknownHostException) { assertPreserved(expected, error) }
        assertEquals(listOf("pinned:dns"), network.events)
        assertTrue(network.closed)
        network.socket.close()
    }

    @Test fun bindingFailureIsNotRetriedAndAllResourcesClose() = runBlocking {
        val expected = IOException("synthetic network bind denied")
        val network = Network("pinned", object : Socket() {
            override fun bind(address: SocketAddress?) { throw expected }
        })
        try {
            measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick)
            fail()
        } catch (error: IOException) { assertPreserved(expected, error) }
        assertTrue(network.closed && network.socket.isClosed)
    }

    @Test fun timeoutIsPreservedAndNotRetried() = runBlocking {
        val expected = SocketTimeoutException("synthetic connect timeout")
        val calls = AtomicInteger()
        val network = Network("pinned", object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                assertEquals(3000, timeout); calls.incrementAndGet(); throw expected
            }
        })
        try {
            measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick)
            fail()
        } catch (error: SocketTimeoutException) { assertPreserved(expected, error) }
        assertEquals(1, calls.get())
        assertTrue(network.closed && network.socket.isClosed)
    }

    @Test fun cancelClosesTheSocketAndNetworkWhileConnectIsInProgress() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                started.countDown()
                check(released.await(2, TimeUnit.SECONDS))
                throw IOException("synthetic socket closed")
            }
            override fun close() { super.close(); released.countDown() }
        }
        val network = Network("pinned", socket)
        val task = launch(Dispatchers.Default) { measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick) }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        withTimeout(3000) { task.cancelAndJoin() }
        assertTrue(network.closed && socket.isClosed)
    }

    @Test fun networkLossCannotPublishLatencyAndPreservesTheReason() = runBlocking {
        val expected = IOException("synthetic pinned network lost")
        lateinit var network: Network
        val socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) { network.failure = expected }
        }
        network = Network("pinned", socket)
        try {
            measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick)
            fail()
        } catch (error: IOException) { assertPreserved(expected, error) }
        assertTrue(network.closed && socket.isClosed)
    }

    @Test fun networkCallbackDoesNotReplaceItsFailureWithSocketClosed() = runBlocking {
        val expected = IOException("synthetic callback: pinned network lost")
        lateinit var signal: (IOException) -> Unit
        val network = Network("pinned", object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                signal(expected)
                throw IOException("synthetic socket closed after callback")
            }
        })
        try {
            measureTcpPing("synthetic.invalid", 443, 3000, { signal = it; network }, ::tick)
            fail()
        } catch (error: IOException) { assertPreserved(expected, error) }
        assertTrue(network.closed && network.socket.isClosed)
    }

    @Test fun concurrentTestsKeepIndependentContextsAndReleaseThem() = runBlocking {
        val networks = (1..12).map { index ->
            Network("network-$index", object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { }
            })
        }
        networks.map { network ->
            async(Dispatchers.Default) {
                measureTcpPing("synthetic.invalid", 443, 3000, { network }, ::tick)
            }
        }.awaitAll()
        for (network in networks) {
            assertTrue(network.closed && network.socket.isClosed)
            assertEquals(listOf("${network.id}:dns", "${network.id}:socket"), network.events)
        }
    }
}
