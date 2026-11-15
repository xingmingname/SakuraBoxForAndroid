package io.nekohasekai.sagernet.bg

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libcore.BoxInstance
import libcore.ConnectionMonitor

/** Only transports native metadata through a private pipe, never a TCP port. */
internal class ConnectionMonitorStreams : AutoCloseable {
    private val access = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val monitors = mutableSetOf<ConnectionMonitor>()
    private var closed = false

    @OptIn(ExperimentalCoroutinesApi::class)
    fun open(box: BoxInstance, connectionID: String): ParcelFileDescriptor? = synchronized(access) {
        if (closed || monitors.size >= 4) return null
        val monitor = try { box.newConnectionMonitor(connectionID) } catch (_: Exception) { return null }
        val descriptors = try { ParcelFileDescriptor.createPipe() } catch (_: Exception) {
            monitor.close()
            return null
        }
        val fd = try { descriptors[1].detachFd() } catch (_: Exception) {
            descriptors.forEach { runCatching { it.close() } }
            monitor.close()
            return null
        }
        monitors.add(monitor)
        // ATOMIC guarantees the native-owned descriptor reaches finally even if
        // the service closes between allocating the pipe and dispatching IO.
        scope.launch(start = CoroutineStart.ATOMIC) {
            try { monitor.writeToFD(fd) } catch (_: Exception) {
                // A closed reader is normal; do not log connection metadata.
            } finally {
                monitor.close()
                synchronized(access) { monitors.remove(monitor) }
            }
        }
        descriptors[0]
    }

    override fun close() {
        val pending = synchronized(access) {
            closed = true
            monitors.toList().also { monitors.clear() }
        }
        pending.forEach { it.close() }
        scope.cancel()
    }
}
