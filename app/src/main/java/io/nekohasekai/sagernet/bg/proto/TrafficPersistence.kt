package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One :bg writer. Failed writes outlive closed cores without retaining those cores.
 * One worker runs while cores, pending deltas or explicit reset intents remain.
 */
object TrafficPersistence {
    const val CHECKPOINT_MS = 10_000L
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val journal = TrafficJournal()
    private val active = mutableSetOf<TrafficLooper>()
    private var worker: Job? = null
    // Retain the original base across failed/ambiguous commits. Replaying an
    // absolute target is idempotent; re-reading a base then adding twice is not.
    private val bases = mutableMapOf<Long, TrafficTotals>()
    private val targets = mutableMapOf<Long, TrafficTotals>()
    private val clears = mutableMapOf<Long, TrafficTotals>()

    suspend fun <T> locked(block: () -> T): T = mutex.withLock { block() }

    fun register(looper: TrafficLooper, ids: Set<Long>) {
        checkpoint()
        ids.chunked(500).forEach { chunk ->
            journal.load(SagerDatabase.proxyDao.getTraffic(chunk).associate {
                it.id to TrafficTotals(it.rx, it.tx)
            }.filterKeys { it !in bases && it !in clears })
        }
        active.add(looper)
        ensureWorker()
    }
    fun unregister(looper: TrafficLooper) {
        active.remove(looper)
        journal.retain(active.flatMap { it.profileIds }.toSet())
    }
    fun credit(id: Long, amount: TrafficTotals) = journal.credit(id, amount)
    fun total(id: Long) = journal.total(id)

    private fun resetRows(ids: Collection<Long>) {
        SagerDatabase.instance.runInTransaction {
            ids.toList().chunked(500).forEach { SagerDatabase.proxyDao.clearTraffic(it) }
        }
    }

    fun checkpoint() {
        try {
            if (clears.isNotEmpty()) {
                resetRows(clears.keys)
                journal.acknowledgeClear(clears)
                clears.keys.forEach { bases.remove(it); targets.remove(it) }
                clears.clear()
            }
            journal.checkpoint { deltas ->
                val saved = mutableMapOf<Long, TrafficTotals>()
                SagerDatabase.instance.runInTransaction {
                    deltas.forEach { (id, amount) ->
                        val row = SagerDatabase.proxyDao.getTraffic(id) ?: return@forEach
                        val current = TrafficTotals(row.rx, row.tx)
                        val base = bases.getOrPut(id) { current }
                        // Configuration saves preserve traffic. An unexpected writer must
                        // not be silently overwritten by a stale checkpoint.
                        val previousTarget = targets[id] ?: base
                        check(current.rx in base.rx..previousTarget.rx &&
                            current.tx in base.tx..previousTarget.tx)
                        val target = base + amount
                        targets[id] = target
                        SagerDatabase.proxyDao.updateTraffic(id, target.rx, target.tx)
                        saved[id] = target
                    }
                }
                saved
            }
            bases.clear()
            targets.clear()
        } catch (_: Exception) {
            Logs.w("Traffic checkpoint failed pending bytes retained for retry")
        }
        journal.retain(active.flatMap { it.profileIds }.toSet())
        ensureWorker()
    }

    private fun hasWork() = active.isNotEmpty() || journal.hasPending() ||
        clears.isNotEmpty()

    private fun ensureWorker() {
        if (hasWork() && worker?.isActive != true) {
            worker = scope.launch {
                while (true) {
                    delay(CHECKPOINT_MS)
                    val keepRunning = mutex.withLock {
                        active.forEach {
                            try { it.sample() }
                            catch (_: Exception) { Logs.w("Traffic sample failed cumulative counters retained") }
                        }
                        checkpoint()
                        hasWork().also {
                            if (!it) worker = null
                        }
                    }
                    if (!keepRunning) break
                }
            }
        }
    }

    suspend fun clear(ids: List<Long>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            active.forEach { it.sample() }
            // Bytes arriving after this cut belong to the next accounting epoch.
            val cut = journal.pendingFor(ids)
            try {
                resetRows(ids)
                journal.acknowledgeClear(cut)
                ids.forEach { bases.remove(it); targets.remove(it); clears.remove(it) }
            } catch (e: Exception) {
                // Retrying an explicit reset is safe even if COMMIT succeeded
                // but its acknowledgement failed. Preserve post-cut bytes.
                clears.putAll(cut)
                ensureWorker()
                throw e
            }
        }
    }
}
