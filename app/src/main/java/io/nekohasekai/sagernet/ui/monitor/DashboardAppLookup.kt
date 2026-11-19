package io.nekohasekai.sagernet.ui.monitor

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import io.nekohasekai.sagernet.bg.proto.TrafficTotals

internal data class DashboardRank(
    val packageName: String,
    val name: String,
    val icon: Drawable?,
    val bytes: TrafficTotals,
    val fraction: Double?,
)

/** Resolves only verified Android owners and limits PackageManager work per snapshot. */
internal class DashboardAppLookup(private val packages: PackageManager) {
    private data class Key(val uid: Int, val packageName: String)
    private data class AppLabel(val text: String?, val checkedAt: Long)
    private val labels = LruCache<Key, AppLabel>(256)
    private val icons = LruCache<String, Drawable>(12)
    private var lookupBudget = 0

    fun beginBatch() {
        lookupBudget = 32
    }

    fun label(uid: Int, packageName: String): String? {
        val key = Key(uid, packageName)
        val now = SystemClock.elapsedRealtime()
        labels.get(key)?.takeIf { now - it.checkedAt < 30_000L }?.let { return it.text }
        if (lookupBudget == 0) return null
        lookupBudget--
        val result = verifiedDashboardLabel(packages, key.uid, key.packageName)
        labels.put(key, AppLabel(result, now))
        return result
    }

    fun rank(status: DashboardStatus?): List<DashboardRank> {
        val session = status?.bytes ?: return emptyList()
        val denominator = session.rx.toDouble() + session.tx
        if (denominator <= 0) return emptyList()
        val known = status.applications.sortedByDescending { it.bytes.rx.toDouble() + it.bytes.tx }.mapNotNull { app ->
            if (app.bytes == TrafficTotals()) return@mapNotNull null
            label(app.uid, app.packageName)?.let { app to it }
        }
        val shareAvailable = known.sumOf { it.first.bytes.rx.toDouble() } <= session.rx &&
            known.sumOf { it.first.bytes.tx.toDouble() } <= session.tx
        return known.sortedWith(compareByDescending<Pair<DashboardAppTraffic, String>> {
            it.first.bytes.rx.toDouble() + it.first.bytes.tx
        }.thenBy { it.first.packageName }).take(8).map { (app, name) ->
            val icon = icons.get(app.packageName) ?: runCatching { packages.getApplicationIcon(app.packageName) }.getOrNull()
                ?.also { icons.put(app.packageName, it) }
            DashboardRank(app.packageName, name, icon, app.bytes,
                if (shareAvailable) (app.bytes.rx.toDouble() + app.bytes.tx) / denominator else null)
        }
    }
}

internal fun verifiedDashboardLabel(packages: PackageManager, uid: Int, packageName: String): String? =
    runCatching {
        if (uid <= 1000 || packages.getPackagesForUid(uid)?.singleOrNull() != packageName) return@runCatching null
        @Suppress("DEPRECATION")
        val info = packages.getApplicationInfo(packageName, 0)
        if (info.uid != uid) null else packages.getApplicationLabel(info).toString().ifBlank { packageName }
    }.getOrNull()
