package moe.matsuri.nb4a.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface PingNetwork : Closeable {
    fun checkNetwork()
    fun lookup(host: String): Array<InetAddress>
    fun createSocket(): Socket
}

internal suspend fun measureTcpPing(
    host: String,
    port: Int,
    timeout: Int,
    acquireNetwork: ((IOException) -> Unit) -> PingNetwork,
    elapsedMillis: () -> Long,
): Int = coroutineScope {
    suspendCancellableCoroutine { continuation ->
        val socketRef = AtomicReference<Socket?>()
        val networkRef = AtomicReference<PingNetwork?>()
        val networkFailure = AtomicReference<IOException?>()
        fun closeSocket() {
            try { socketRef.get()?.close() } catch (_: IOException) { }
        }
        continuation.invokeOnCancellation {
            closeSocket()
            networkRef.get()?.close()
        }
        launch(Dispatchers.IO) {
            try {
                val network = acquireNetwork { error ->
                    networkFailure.compareAndSet(null, error)
                    closeSocket()
                }
                networkRef.set(network)
                val latency = network.use {
                    coroutineContext.ensureActive()
                    network.checkNetwork()
                    val address = network.lookup(host).firstOrNull()
                        ?: throw UnknownHostException("Test network returned no address")
                    coroutineContext.ensureActive()
                    network.createSocket().use { socket ->
                        socketRef.set(socket)
                        coroutineContext.ensureActive()
                        network.checkNetwork()
                        socket.soTimeout = timeout
                        socket.bind(InetSocketAddress(0))
                        val start = elapsedMillis()
                        socket.connect(InetSocketAddress(address, port), timeout)
                        val result = (elapsedMillis() - start).toInt()
                        coroutineContext.ensureActive()
                        network.checkNetwork()
                        result
                    }
                }
                continuation.resume(latency)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(networkFailure.get() ?: error)
            } finally {
                closeSocket()
            }
        }
    }
}
