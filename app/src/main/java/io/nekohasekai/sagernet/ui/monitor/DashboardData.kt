package io.nekohasekai.sagernet.ui.monitor

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.bg.proto.TrafficTotals
import java.util.ArrayDeque

data class DashboardAppTraffic(val uid: Int, val packageName: String, val bytes: TrafficTotals)

data class DashboardStatus(
    val state: Int,
    val started: Long,
    val tun: Boolean,
    val name: String,
    val protocol: String,
    val hops: List<String>,
    val hopCount: Int,
    val sampledAt: Long,
    val bytes: TrafficTotals?,
    val applications: List<DashboardAppTraffic>,
)

internal data class DashboardRate(val at: Long, val bytes: TrafficTotals)

internal fun dashboardRate(now: TrafficTotals, before: TrafficTotals, elapsedMs: Long): TrafficTotals? {
    if (elapsedMs <= 0 || now.rx < before.rx || now.tx < before.tx) return null
    return TrafficTotals(((now.rx - before.rx) * (1000.0 / elapsedMs)).toLong(),
        ((now.tx - before.tx) * (1000.0 / elapsedMs)).toLong())
}

internal class DashboardSpeedHistory {
    private var started = 0L
    private var previous: DashboardRate? = null
    private val samples = ArrayDeque<DashboardRate>()

    fun pause() { previous = null }

    fun update(status: DashboardStatus?): TrafficTotals? {
        val session = status?.started ?: 0L
        if (session != started) {
            started = session
            previous = null
            samples.clear()
        }
        val bytes = status?.bytes ?: run { previous = null; return null }
        val sample = DashboardRate(status.sampledAt, bytes)
        val old = previous
        if (old != null && sample.at - old.at < 500L) return samples.peekLast()?.bytes
        previous = sample
        while (samples.isNotEmpty() &&
            (samples.size >= 2001 || sample.at - samples.peekFirst().at > 30 * 60_000L)) {
            samples.removeFirst()
        }
        if (old == null || sample.at - old.at > 4000) return null
        val rate = dashboardRate(bytes, old.bytes, sample.at - old.at) ?: return null
        samples.add(DashboardRate(sample.at, rate))
        return rate
    }

    fun points(): List<DashboardRate> = samples.toList()
}

internal fun decodeDashboardTraffic(payload: String?, includeApps: Boolean): Pair<TrafficTotals?, List<DashboardAppTraffic>?> {
    if (payload == null) return null to if (includeApps) emptyList() else null
    val json = JsonParser.parseString(payload).asJsonObject
    val bytes = TrafficTotals(json["rx"].asLong.coerceAtLeast(0), json["tx"].asLong.coerceAtLeast(0))
    val apps = if (includeApps) json.getAsJsonArray("applications")?.take(256)?.map {
        val app = it.asJsonObject
        DashboardAppTraffic(app["uid"].asInt, app["package"].asString,
            TrafficTotals(app["rx"].asLong.coerceAtLeast(0), app["tx"].asLong.coerceAtLeast(0)))
    }.orEmpty() else null
    return bytes to apps
}

internal data class DashboardConnection(
    val connection: MonitoredConnection,
    val appName: String?,
    val rate: TrafficTotals?,
) {
    val speed get() = rate?.let { it.rx.toDouble() + it.tx } ?: -1.0
}

internal data class DashboardFilter(
    val query: String = "",
    val network: String = "",
    val bySpeed: Boolean = false,
    val expanded: Boolean = false,
    val page: Int = 0,
)

internal data class DashboardPage(val rows: List<DashboardConnection>, val total: Int, val page: Int, val pages: Int)

internal fun filterDashboardConnections(rows: List<DashboardConnection>, filter: DashboardFilter): DashboardPage {
    val query = filter.query.trim()
    val matching = rows.asSequence()
        .filter { filter.network.isEmpty() || it.connection.network.equals(filter.network, ignoreCase = true) }
        .filter {
            query.isEmpty() || it.connection.domain.contains(query, ignoreCase = true) ||
                it.connection.destination.contains(query, ignoreCase = true) ||
                it.appName?.contains(query, ignoreCase = true) == true ||
                (it.appName != null && it.connection.packages.any { name -> name.contains(query, ignoreCase = true) })
        }
        .toList()
    val sorted = if (filter.bySpeed) matching.sortedWith(compareByDescending<DashboardConnection> { it.speed }
        .thenBy { it.connection.createdAt }.thenBy { it.connection.id }) else matching
    val size = if (filter.expanded) 32 else 5
    val pages = if (filter.expanded) ((sorted.size + size - 1) / size).coerceAtLeast(1) else 1
    val page = filter.page.coerceIn(0, pages - 1)
    return DashboardPage(sorted.drop(page * size).take(size), sorted.size, page, pages)
}
