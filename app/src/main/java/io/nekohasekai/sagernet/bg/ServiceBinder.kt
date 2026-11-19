package io.nekohasekai.sagernet.bg

import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.RemoteException
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.BaseService.Data
import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.bg.proto.TrafficPersistence
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.Protocols

internal class ServiceBinder(@Volatile private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
    AutoCloseable {
    private val callbacks = RemoteCallbackList<ISagerNetServiceCallback>()
    private val callbacksAccess = Any()
    private var callbacksClosed = false

    override val coroutineContext = Dispatchers.Main.immediate + Job()

    override fun getState(): Int = (data?.state ?: State.Idle).ordinal
    override fun getProfileName(): String = (data?.proxy?.displayProfileName ?: "Idle")

    override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
        if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
            Runtime.getRuntime().exit(0)
            return
        }
        synchronized(callbacksAccess) {
            if (callbacksClosed) return
            // Cookies share RemoteCallbackList's binder identity and death cleanup.
            callbacks.unregister(cb)
            callbacks.register(cb, id)
        }
    }

    override fun clearTraffic(profileIds: LongArray): Boolean = runBlocking(Dispatchers.IO) {
        try {
            TrafficPersistence.clear(profileIds.toList())
            true
        } catch (_: Exception) {
            Logs.w("Traffic reset failed existing totals retained")
            false
        }
    }

    private val connectionMonitors = ConnectionMonitorStreams()

    override fun openConnectionMonitor(connectionId: String): ParcelFileDescriptor? {
        val current = data ?: return null
        if (current.state != State.Connected) return null
        return current.proxy?.box?.let { connectionMonitors.open(it, connectionId) }
    }

    override fun getDashboardStatus(includeApplications: Boolean): android.os.Bundle {
        val current = data ?: return android.os.Bundle().apply { putInt("state", State.Idle.ordinal) }
        val state = current.state
        val running = current.proxy
        val start = current.connectedAt
        val result = android.os.Bundle().apply { putInt("state", state.ordinal) }
        if (state != State.Connected || running == null || start == 0L) return result
        val tun = current.tunRunning
        val selector = running.config.selectorGroupId >= 0
        val tag = if (selector) running.box.dashboardSelectedOutbound()
            else running.config.profileTagMap[running.profile.id]
        val route = tag?.let { running.config.dashboardRoutes[it] }
        val traffic = if (tun) runCatching { running.looper?.dashboardTraffic(includeApplications) }.getOrNull() else null
        if (current.state != state || current.proxy !== running || current.connectedAt != start) {
            return android.os.Bundle().apply { putInt("state", current.state.ordinal) }
        }
        return result.apply {
            putLong("started", start)
            putBoolean("tun", tun)
            putString("name", route?.name ?: running.dashboardName.takeUnless { selector })
            putString("protocol", route?.protocol ?: running.dashboardProtocol.takeUnless { selector })
            putStringArrayList("hops", ArrayList(route?.hops.orEmpty()))
            putInt("hopCount", route?.hopCount ?: 0)
            putString("traffic", traffic)
        }
    }

    private fun callbackSnapshot(id: Int?): List<ISagerNetServiceCallback> =
        synchronized(callbacksAccess) {
            if (callbacksClosed) return@synchronized emptyList()
            val count = callbacks.beginBroadcast()
            try {
                buildList {
                    repeat(count) { index ->
                        if (id == null || callbacks.getBroadcastCookie(index) == id) {
                            add(callbacks.getBroadcastItem(index))
                        }
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }

    suspend fun broadcast(id: Int? = null, work: (ISagerNetServiceCallback) -> Unit) {
        // No registry lock or RemoteCallbackList broadcast is held across IPC.
        for (callback in callbackSnapshot(id)) {
            try { work(callback) }
            catch (_: RemoteException) {}
            catch (_: Exception) {}
        }
    }

    override fun unregisterCallback(cb: ISagerNetServiceCallback) {
        synchronized(callbacksAccess) { callbacks.unregister(cb) }
    }

    override fun urlTest(): Int {
        if (data?.proxy?.box == null) {
            error("core not started")
        }
        try {
            return Libcore.urlTest(
                data!!.proxy!!.box, DataStore.connectionTestURL, 3000
            )
        } catch (e: Exception) {
            error(Protocols.genFriendlyMsg(e.readableMessage))
        }
    }

    fun stateChanged(s: State, msg: String?) = launch {
        val profileName = profileName
        broadcast { it.stateChanged(s.ordinal, profileName, msg) }
    }

    fun missingPlugin(pluginName: String) = launch {
        val profileName = profileName
        broadcast { it.missingPlugin(profileName, pluginName) }
    }

    override fun close() {
        connectionMonitors.close()
        synchronized(callbacksAccess) {
            callbacksClosed = true
            callbacks.kill()
        }
        cancel()
        data = null
    }
}
