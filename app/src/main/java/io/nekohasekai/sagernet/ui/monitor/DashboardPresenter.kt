package io.nekohasekai.sagernet.ui.monitor

import android.content.Context
import io.nekohasekai.sagernet.bg.proto.TrafficTotals

internal data class DashboardFrame(
    val monitor: ConnectionMonitorState,
    val rate: TrafficTotals?,
    val points: List<DashboardRate>,
    val connections: DashboardPage,
    val apps: List<DashboardRank>,
)

/** All package lookups, rate calculations and filtering are performed off the UI thread. */
internal class DashboardPresenter(context: Context) {
    private val applications = DashboardAppLookup(context.applicationContext.packageManager)
    private var previousConnections = emptyMap<String, DashboardConnection>()
    private var previousSession = 0L
    private val speed = DashboardSpeedHistory()
    private var last: ConnectionMonitorState? = null
    private var rows = emptyList<DashboardConnection>()
    private var points = emptyList<DashboardRate>()
    private var rate: TrafficTotals? = null
    private var ranks = emptyList<DashboardRank>()
    private var lastApplications: List<DashboardAppTraffic>? = null

    fun pause() {
        speed.pause()
        previousConnections = emptyMap()
        last = null
    }

    fun frame(state: ConnectionMonitorState, filter: DashboardFilter): DashboardFrame {
        if (last !== state) {
            val session = state.dashboard?.started ?: 0
            if (session != previousSession) {
                previousConnections = emptyMap()
                lastApplications = null
                ranks = emptyList()
            }
            previousSession = session
            val appTraffic = state.dashboard?.applications
            if (appTraffic !== lastApplications) {
                applications.beginBatch()
                ranks = applications.rank(state.dashboard)
                lastApplications = appTraffic
            }
            applications.beginBatch()
            rows = state.connections.map { item ->
                val old = previousConnections[item.id]
                val bytes = TrafficTotals(item.download, item.upload)
                val elapsed = item.duration - (old?.connection?.duration ?: 0)
                val rate = when {
                    old == null -> null
                    elapsed == 0L && item.download == old.connection.download && item.upload == old.connection.upload -> old.rate
                    elapsed in 1..4000 -> dashboardRate(bytes,
                        TrafficTotals(old.connection.download, old.connection.upload), elapsed)
                    else -> null
                }
                val packageName = item.packages.singleOrNull()
                val name = if (packageName != null && item.ownerUid != null) {
                    applications.label(item.ownerUid, packageName)
                } else null
                DashboardConnection(item, name, rate)
            }
            previousConnections = rows.associateBy { it.connection.id }
            rate = when {
                state.dashboard != null -> speed.update(state.dashboard)
                state.connected == false -> speed.update(null)
                else -> { speed.pause(); null }
            }
            points = if (state.dashboard?.bytes != null) speed.points() else emptyList()
            last = state
        }
        return DashboardFrame(state, rate, points, filterDashboardConnections(rows, filter), ranks)
    }
}
