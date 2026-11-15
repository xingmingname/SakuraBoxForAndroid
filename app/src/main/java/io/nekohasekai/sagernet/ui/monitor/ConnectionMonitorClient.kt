package io.nekohasekai.sagernet.ui.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** A visible screen owns the binding and its pipe; no records go to disk. */
class ConnectionMonitorClient(
    context: Context,
    private val detailID: String? = null,
) {
    private val context = context.applicationContext
    private val epoch = AtomicLong()
    private val mutableState = MutableStateFlow(ConnectionMonitorState())
    val state = mutableState.asStateFlow()
    private var binding: ServiceConnection? = null
    private var reader: Job? = null
    private val pipeAccess = Any()
    private var pipe: ParcelFileDescriptor? = null

    fun start(scope: CoroutineScope) {
        stop()
        val generation = epoch.incrementAndGet()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                if (epoch.get() != generation) return
                val service = ISagerNetService.Stub.asInterface(binder) ?: return
                reader?.cancel()
                closePipe()
                reader = scope.launch(Dispatchers.IO) {
                    while (isActive && epoch.get() == generation) {
                        var descriptor: ParcelFileDescriptor? = null
                        try {
                            val connected = service.state == BaseService.State.Connected.ordinal
                            if (!isActive) return@launch
                            publish(generation, ConnectionMonitorState(connected = connected))
                            if (!connected) { delay(1000); continue }
                            val opened = service.openConnectionMonitor(detailID.orEmpty())
                            descriptor = opened
                            if (opened != null) {
                                synchronized(pipeAccess) {
                                    if (epoch.get() == generation && isActive) pipe = opened
                                    else { opened.close(); return@launch }
                                }
                                val store = ConnectionMonitorStore(detailID)
                                ParcelFileDescriptor.AutoCloseInputStream(opened).bufferedReader(Charsets.UTF_8).use { input ->
                                    while (isActive && epoch.get() == generation) {
                                        val line = input.readLine() ?: break
                                        if (!store.accept(line)) continue
                                        if (!isActive) return@launch
                                        publish(generation, store.snapshot())
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Metadata and raw errors must never leak to logs.
                        } finally {
                            synchronized(pipeAccess) {
                                if (pipe === descriptor) pipe = null
                            }
                            runCatching { descriptor?.close() }
                        }
                        if (isActive) {
                            val connected = runCatching { service.state }.getOrNull()
                                ?.let { it == BaseService.State.Connected.ordinal }
                            if (!isActive) return@launch
                            publish(generation, ConnectionMonitorState(connected = connected))
                            delay(1000)
                        }
                    }
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                if (epoch.get() == generation) start(scope)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (epoch.get() != generation) return
                reader?.cancel()
                closePipe()
                publish(generation, ConnectionMonitorState())
            }
        }
        binding = connection
        val bound = runCatching {
            context.bindService(Intent(context, SagerConnection.serviceClass).setAction(Action.SERVICE),
                connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            binding = null
            publish(generation, ConnectionMonitorState())
        }
    }

    private fun publish(generation: Long, value: ConnectionMonitorState) {
        synchronized(pipeAccess) {
            if (epoch.get() == generation) mutableState.value = value
        }
    }

    private fun closePipe() {
        val previous = synchronized(pipeAccess) { pipe.also { pipe = null } }
        runCatching { previous?.close() }
    }

    fun stop() {
        synchronized(pipeAccess) {
            epoch.incrementAndGet()
            mutableState.value = ConnectionMonitorState()
        }
        reader?.cancel()
        reader = null
        closePipe()
        binding?.let { runCatching { context.unbindService(it) } }
        binding = null
    }
}
