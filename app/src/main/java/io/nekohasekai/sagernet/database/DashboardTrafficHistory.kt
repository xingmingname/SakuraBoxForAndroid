package io.nekohasekai.sagernet.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TrafficTotals
import io.nekohasekai.sagernet.ktx.Logs
import java.util.UUID

internal const val DASHBOARD_HOUR_MS = 3_600_000L
internal const val DASHBOARD_DAY_HOURS = 24L
private const val RETAINED_HOURS = 35 * DASHBOARD_DAY_HOURS

internal fun dashboardHour(timeMs: Long): Long = timeMs.floorDiv(DASHBOARD_HOUR_MS)

internal data class DashboardHistory(
    val total: TrafficTotals?,
    val firstHour: Long?,
    val buckets: List<TrafficTotals?>,
)

internal data class DashboardHistoryBatch(
    val id: String,
    val total: TrafficTotals,
    val hours: Map<Long, TrafficTotals>,
)

/** Owned by the existing traffic checkpoint mutex in the service process. */
internal class DashboardHistoryJournal(private val nextId: () -> String = { UUID.randomUUID().toString() }) {
    private val hours = sortedMapOf<Long, TrafficTotals>()
    private var total = TrafficTotals()
    private var retry: DashboardHistoryBatch? = null

    fun credit(hour: Long, bytes: TrafficTotals) {
        if (bytes == TrafficTotals()) return
        val updated = (hours[hour] ?: TrafficTotals()) + bytes
        total += bytes
        hours[hour] = updated
        // Bound an arbitrarily long write failure. Lifetime bytes remain in total.
        while (hours.size > RETAINED_HOURS) hours.remove(hours.firstKey())
    }

    fun hasPending() = retry != null || total != TrafficTotals()

    fun checkpoint(save: (DashboardHistoryBatch) -> Unit) {
        while (hasPending()) {
            val batch = retry ?: DashboardHistoryBatch(nextId(), total, hours.toMap()).also {
                retry = it
                total = TrafficTotals()
                hours.clear()
            }
            save(batch)
            retry = null
        }
    }
}

internal object DashboardTrafficHistory {
    private val database by lazy { Database(SagerNet.application) }
    private val journal = DashboardHistoryJournal()

    fun credit(hour: Long, bytes: TrafficTotals) = journal.credit(hour, bytes)
    fun hasPending() = journal.hasPending()

    fun checkpoint() {
        try {
            journal.checkpoint { batch -> database.save(batch) }
        } catch (_: Exception) {
            Logs.w("Dashboard history checkpoint failed; pending data retained")
        }
    }

    // A single read transaction keeps lifetime totals and the plotted interval consistent.
    fun read(nowMs: Long, days: Int): DashboardHistory {
        require(days == 1 || days == 7 || days == 30)
        val currentHour = dashboardHour(nowMs)
        val today = currentHour.floorDiv(DASHBOARD_DAY_HOURS) * DASHBOARD_DAY_HOURS
        val start = today - (days - 1L) * DASHBOARD_DAY_HOURS
        val step = if (days == 1) 1L else DASHBOARD_DAY_HOURS
        val points = MutableList<TrafficTotals?>(if (days == 1) 24 else days) { null }
        var total: TrafficTotals? = null
        var first: Long? = null
        database.readableDatabase.rawQuery(
            "SELECT totals.rx, totals.tx, first_hour, hour, hours.rx, hours.tx " +
                "FROM totals LEFT JOIN hours ON hour BETWEEN ? AND ? WHERE totals.id = 1",
            arrayOf(start.toString(), currentHour.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (total == null) {
                    total = TrafficTotals(cursor.getLong(0), cursor.getLong(1))
                    first = cursor.getLong(2)
                }
                if (!cursor.isNull(3)) {
                    val index = ((cursor.getLong(3) - start) / step).toInt()
                    points[index] = (points[index] ?: TrafficTotals()) +
                        TrafficTotals(cursor.getLong(4), cursor.getLong(5))
                }
            }
        }
        return DashboardHistory(total, first, points)
    }

    private class Database(context: Context) : SQLiteOpenHelper(context, "dashboard-traffic.db", null, 1) {
        init { setWriteAheadLoggingEnabled(true) }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE totals (id INTEGER PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL, first_hour INTEGER NOT NULL, last_batch TEXT NOT NULL)")
            db.execSQL("CREATE TABLE hours (hour INTEGER PRIMARY KEY, rx INTEGER NOT NULL, tx INTEGER NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            error("Unsupported dashboard history schema upgrade")

        fun save(batch: DashboardHistoryBatch) {
            val db = writableDatabase
            db.beginTransactionNonExclusive()
            try {
                db.rawQuery("SELECT last_batch FROM totals WHERE id = 1", null).use {
                    if (it.moveToFirst() && it.getString(0) == batch.id) {
                        db.setTransactionSuccessful()
                        return
                    }
                }
                val first = batch.hours.keys.minOrNull() ?: dashboardHour(System.currentTimeMillis())
                db.execSQL("INSERT OR IGNORE INTO totals VALUES (1, 0, 0, ?, '')", arrayOf(first))
                db.execSQL("UPDATE totals SET rx = rx + ?, tx = tx + ?, first_hour = MIN(first_hour, ?), last_batch = ? WHERE id = 1",
                    arrayOf(batch.total.rx, batch.total.tx, first, batch.id))
                for ((hour, bytes) in batch.hours) {
                    db.execSQL("INSERT OR IGNORE INTO hours VALUES (?, 0, 0)", arrayOf(hour))
                    db.execSQL("UPDATE hours SET rx = rx + ?, tx = tx + ? WHERE hour = ?", arrayOf(bytes.rx, bytes.tx, hour))
                }
                // A wall-clock correction must not immediately prune the entire visible history.
                db.execSQL("DELETE FROM hours WHERE hour IN (SELECT hour FROM hours ORDER BY hour DESC LIMIT -1 OFFSET ?)",
                    arrayOf(RETAINED_HOURS))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}
