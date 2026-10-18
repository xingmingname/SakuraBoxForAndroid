package moe.matsuri.nb4a.net

import android.os.Build
import android.os.ParcelFileDescriptor
import android.test.AndroidTestCase
import java.io.IOException
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Compile here; execute only on an explicitly available Android test device. */
class URLTestNetworkTest : AndroidTestCase() {
    fun testBindingKeepsTCPAndUDPDescriptorsOpen() {
        // fromSocket/fromDatagramSocket ownership differs before API 29.
        if (Build.VERSION.SDK_INT < 29) return
        TestNetworkContext.acquire { throw AssertionError(it) }.use { network ->
            assertTrue(network.resolver.networkHandle() != 0L)
            ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 2000
                val peerError = AtomicReference<Throwable?>()
                val peer = thread {
                    try {
                        server.accept().use { it.getOutputStream().write(42) }
                    } catch (error: IOException) {
                        peerError.set(error)
                    }
                }
                Socket().use { socket ->
                    socket.bind(InetSocketAddress(0))
                    ParcelFileDescriptor.fromSocket(socket).use { descriptor ->
                        network.bindSocket(descriptor.fd)
                        assertTrue(descriptor.fileDescriptor.valid())
                    }
                    socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 1000)
                    socket.soTimeout = 1000
                    assertEquals(42, socket.getInputStream().read())
                }
                peer.join(2000)
                assertFalse(peer.isAlive)
                assertNull(peerError.get())
            }
            DatagramSocket().use { socket ->
                ParcelFileDescriptor.fromDatagramSocket(socket).use { descriptor ->
                    network.bindSocket(descriptor.fd)
                    assertTrue(descriptor.fileDescriptor.valid())
                }
                assertFalse(socket.isClosed)
                DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).use { receiver ->
                    receiver.soTimeout = 1000
                    socket.send(DatagramPacket(byteArrayOf(42), 1, receiver.localSocketAddress))
                    val packet = DatagramPacket(ByteArray(1), 1)
                    receiver.receive(packet)
                    assertEquals(42, packet.data[0].toInt())
                }
            }
        }
    }

    fun testClosedNetworkFailsBeforeDescriptorAccess() {
        if (Build.VERSION.SDK_INT < 23) return
        val network = TestNetworkContext.acquire { }
        network.close()
        network.close()
        try {
            network.bindSocket(-1)
            fail("Closed test network accepted a socket")
        } catch (_: IOException) { }
    }

    fun testAPI21And22HaveExplicitCompatibilityError() {
        if (Build.VERSION.SDK_INT >= 23) return
        try {
            TestNetworkContext.acquire { }
            fail("Legacy API silently bypassed network binding")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("API 21/22"))
        }
    }
}
