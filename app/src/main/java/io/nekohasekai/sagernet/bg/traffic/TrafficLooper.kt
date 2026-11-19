package io.nekohasekai.sagernet.bg.proto

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DashboardTrafficHistory
import io.nekohasekai.sagernet.database.dashboardHour
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*

class TrafficLooper(private val data: BaseService.Data, private val proxy: ProxyInstance) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var source: libcore.ProfileTrafficStats
    private lateinit var ledger: TrafficLedger
    private var ids = emptySet<Long>()
    internal val profileIds: Set<Long> get() = ids
    private var persist = false
    private var finished = false
    private var prepared = false
    private var previousTime = System.nanoTime()
    private var displayedSession = TrafficTotals()
    private var bypass = TrafficTotals()
    private var displayedBypass = TrafficTotals()
    private var lastDashboard = TrafficTotals()

    fun dashboardTraffic(includeApplications: Boolean): String = source.dashboardTraffic(includeApplications)

    internal fun checkpointDashboard() {
        if (!prepared || !persist) return
        try {
            val snapshot = JsonParser.parseString(dashboardTraffic(false)).asJsonObject
            val current = TrafficTotals(snapshot["rx"].asLong, snapshot["tx"].asLong)
            val delta = TrafficTotals((current.rx - lastDashboard.rx).coerceAtLeast(0),
                (current.tx - lastDashboard.tx).coerceAtLeast(0))
            DashboardTrafficHistory.credit(dashboardHour(System.currentTimeMillis()), delta)
            lastDashboard = current
        } catch (_: Exception) {
            Logs.w("Dashboard sample failed; cumulative counters retained")
        }
    }

    suspend fun prepare() = withContext(Dispatchers.IO) { TrafficPersistence.locked {
        val owners = proxy.config.trafficMap.mapValues { (_, nodes) -> nodes.map { it.id }.toSet() }
        ids = owners.values.flatten().toSet()
        persist = DataStore.profileTrafficStatistics
        ledger = TrafficLedger(owners, TAG_BYPASS)
        source = proxy.box.newProfileTrafficStats(
            (owners.keys + TAG_PROXY + TAG_BYPASS).joinToString("\n"),
            if (proxy.config.selectorGroupId >= 0) TAG_PROXY else "",
        )
        TrafficPersistence.register(this@TrafficLooper, ids)
        prepared = true
        previousTime = System.nanoTime()
    } }

    fun start() {
        check(job == null)
        job = scope.launch {
            val displayInterval = DataStore.speedInterval.toLong()
            if (displayInterval <= 0) return@launch // the independent checkpoint worker remains active
            val showDirectSpeed = DataStore.showDirectSpeed
            // Persistence remains active with speed display disabled or the app in background.
            val interval = minOf(displayInterval, 1000L)
            val displayIntervalNanos = displayInterval * 1_000_000
            var lastDisplay = 0L
            while (isActive) {
                val update = TrafficPersistence.locked {
                    sample()
                    val now = System.nanoTime()
                    val elapsed = (now - previousTime).coerceAtLeast(1)
                    val previousSession = displayedSession
                    val previousBypass = displayedBypass
                    previousTime = now
                    displayedSession = ledger.session
                    displayedBypass = bypass
                    // Each sample advances the rate baseline. Only published samples need UI rows.
                    if (now - lastDisplay < displayIntervalNanos) return@locked null
                    lastDisplay = now
                    fun rate(n: Long) = (n.toDouble() * 1_000_000_000 / elapsed).toLong()
                    val speed = SpeedDisplayData(
                        rate(displayedSession.tx - previousSession.tx),
                        rate(displayedSession.rx - previousSession.rx),
                        if (showDirectSpeed) rate(displayedBypass.tx - previousBypass.tx) else 0,
                        if (showDirectSpeed) rate(displayedBypass.rx - previousBypass.rx) else 0,
                        displayedSession.tx, displayedSession.rx,
                    )
                    speed to traffic()
                }
                if (update != null) publish(update.first, update.second)
                delay(interval)
            }
        }
    }

    // Called only under TrafficPersistence's mutex, including clear and final settlement.
    internal fun sample() {
        val values = JsonParser.parseString(source.snapshot()).asJsonObject.entrySet().associate { (tag, item) ->
            tag to TrafficTotals(item.asJsonObject["rx"].asLong, item.asJsonObject["tx"].asLong)
        }
        bypass = values[TAG_BYPASS] ?: TrafficTotals()
        ledger.sample(values) { id, delta -> if (persist) TrafficPersistence.credit(id, delta) }
    }

    private fun traffic() = if (!persist) emptyList() else ids.map { id ->
        TrafficPersistence.total(id).let { TrafficData(id = id, rx = it.rx, tx = it.tx) }
    }

    private suspend fun publish(speed: SpeedDisplayData, traffic: List<TrafficData>) {
        try {
            if (data.state == BaseService.State.Connected) {
                data.binder.broadcast(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) { callback ->
                    callback.cbSpeedUpdate(speed)
                    traffic.forEach { callback.cbTrafficUpdate(it) }
                }
            }
            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(speed)
            }
        } catch (_: Exception) {
            // A detached UI must not terminate sampling or durable checkpoints.
            Logs.d("Traffic display callback unavailable")
        }
    }

    suspend fun checkpoint() = TrafficPersistence.locked {
        if (!finished && prepared) {
            try { sample() } finally {
                checkpointDashboard()
                TrafficPersistence.checkpoint()
            }
        }
    }

    suspend fun pause() { job?.cancelAndJoin() }

    /** Invoked after the data plane closes, while cumulative native counters remain readable. */
    suspend fun finish() {
        val final = TrafficPersistence.locked {
            if (finished || !prepared) { scope.cancel(); return@locked emptyList<TrafficData>() }
            try {
                try { sample() } finally {
                    checkpointDashboard()
                    TrafficPersistence.checkpoint()
                }
                traffic()
            } finally {
                finished = true
                TrafficPersistence.unregister(this)
                scope.cancel()
            }
        }
        try {
            data.binder.broadcast { callback -> final.forEach { callback.cbTrafficUpdate(it) } }
        } catch (_: Exception) {
            Logs.d("Final traffic display callback unavailable")
        }
        final.forEach { item ->
            try { ProfileManager.postUpdate(item.id) }
            catch (_: Exception) { Logs.d("Final traffic display callback unavailable") }
        }
    }
}
