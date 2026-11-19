package io.nekohasekai.sagernet.ui.monitor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
    private val dashboard: Boolean = false,
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
                    var session = 0L
                    var lastAppsAt = -5000L
                    var apps = emptyList<DashboardAppTraffic>()
                    fun status(): DashboardStatus? {
                        if (!dashboard) return null
                        return runCatching {
                            val now = SystemClock.elapsedRealtime()
                            val includeApps = now - lastAppsAt >= 5000L
                            val bundle = service.getDashboardStatus(includeApps)
                            val started = bundle.getLong("started")
                            if (session != started) {
                                session = started
                                apps = emptyList()
                                lastAppsAt = -5000L
                            }
                            val (bytes, freshApps) = decodeDashboardTraffic(bundle.getString("traffic"), includeApps)
                            if (freshApps != null) {
                                lastAppsAt = now
                                apps = freshApps
                            }
                            DashboardStatus(bundle.getInt("state"), started, bundle.getBoolean("tun"),
                                bundle.getString("name").orEmpty(), bundle.getString("protocol").orEmpty(),
                                bundle.getStringArrayList("hops").orEmpty(), bundle.getInt("hopCount"),
                                SystemClock.elapsedRealtime(), bytes, apps)
                        }.getOrNull()
                    }

                    while (isActive && epoch.get() == generation) {
                        var descriptor: ParcelFileDescriptor? = null
                        try {
                            val initial = status()
                            val connected = (initial?.state ?: service.state) == BaseService.State.Connected.ordinal
                            if (!isActive) return@launch
                            publish(generation, ConnectionMonitorState(connected = connected, dashboard = initial))
                            if (!connected) { delay(1000); continue }
                            val opened = service.openConnectionMonitor(detailID.orEmpty())
                            descriptor = opened
                            if (opened != null) {
                                synchronized(pipeAccess) {
                                    if (epoch.get() == generation && isActive) pipe = opened
                                    else { opened.close(); return@launch }
                                }
                                val store = ConnectionMonitorStore(detailID)
                                var lastPublish = -1000L
                                ParcelFileDescriptor.AutoCloseInputStream(opened).bufferedReader(Charsets.UTF_8).use { input ->
                                    while (isActive && epoch.get() == generation) {
                                        val line = input.readLine() ?: break
                                        if (!store.accept(line)) continue
                                        val now = SystemClock.elapsedRealtime()
                                        if (dashboard && now - lastPublish < 900L) continue
                                        lastPublish = now
                                        val current = status()
                                        if (!isActive) return@launch
                                        if (current != null && (current.state != BaseService.State.Connected.ordinal ||
                                                (initial?.started ?: 0L) != 0L && current.started != initial?.started)) break
                                        publish(generation, store.snapshot().copy(dashboard = current))
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
                            val current = status()
                            val connected = (current?.state ?: runCatching { service.state }.getOrNull())
                                ?.let { it == BaseService.State.Connected.ordinal }
                            if (!isActive) return@launch
                            publish(generation, ConnectionMonitorState(connected = connected, dashboard = current))
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
