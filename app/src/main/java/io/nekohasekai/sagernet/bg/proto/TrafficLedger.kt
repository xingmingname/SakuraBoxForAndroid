package io.nekohasekai.sagernet.bg.proto

data class TrafficTotals(val rx: Long = 0, val tx: Long = 0) {
    operator fun plus(other: TrafficTotals): TrafficTotals {
        require(rx >= 0 && tx >= 0 && other.rx >= 0 && other.tx >= 0)
        require(other.rx <= Long.MAX_VALUE - rx && other.tx <= Long.MAX_VALUE - tx)
        return TrafficTotals(rx + other.rx, tx + other.tx)
    }
}

/** Access is serialized by TrafficPersistence, never by UI refresh callbacks. */
class TrafficLedger(
    private val owners: Map<String, Set<Long>>,
    private val bypassTag: String,
) {
    private val previous = mutableMapOf<String, TrafficTotals>()
    var session = TrafficTotals()
        private set

    fun sample(values: Map<String, TrafficTotals>, credit: (Long, TrafficTotals) -> Unit) {
        values.forEach { (tag, value) ->
            require(value.rx >= 0 && value.tx >= 0)
            val old = previous[tag]
            val oldRx = old?.rx ?: 0L
            val oldTx = old?.tx ?: 0L
            // Always advance the epoch boundary, including a lower or idle counter.
            previous[tag] = value
            val rx = if (value.rx >= oldRx) value.rx - oldRx else 0L
            val tx = if (value.tx >= oldTx) value.tx - oldTx else 0L
            if (rx == 0L && tx == 0L) return@forEach
            val delta = TrafficTotals(rx, tx)
            if (tag != bypassTag) session += delta
            owners[tag].orEmpty().forEach { credit(it, delta) }
        }
    }
}

/** Failed transactions retain their exact pending deltas for a later retry. */
class TrafficJournal {
    private val committed = mutableMapOf<Long, TrafficTotals>()
    private val pending = mutableMapOf<Long, TrafficTotals>()

    fun load(values: Map<Long, TrafficTotals>) { committed.putAll(values) }
    fun credit(id: Long, delta: TrafficTotals) {
        if (delta.rx != 0L || delta.tx != 0L) pending[id] = (pending[id] ?: TrafficTotals()) + delta
    }
    fun total(id: Long) = (committed[id] ?: TrafficTotals()) + (pending[id] ?: TrafficTotals())
    fun hasPending() = pending.isNotEmpty()
    fun retain(activeIds: Set<Long>) { committed.keys.retainAll(activeIds + pending.keys) }
    fun checkpoint(store: (Map<Long, TrafficTotals>) -> Map<Long, TrafficTotals>) {
        if (pending.isEmpty()) return
        val saved = store(pending.toMap()) // returns only after the database transaction commits
        committed.putAll(saved)
        pending.clear() // no suspension or notification between commit and acknowledgement
    }
    fun pendingFor(ids: Collection<Long>) = ids.associateWith { pending[it] ?: TrafficTotals() }
    fun acknowledgeClear(cut: Map<Long, TrafficTotals>) {
        cut.forEach { (id, before) ->
            val now = pending[id] ?: TrafficTotals()
            val rest = TrafficTotals(now.rx - before.rx, now.tx - before.tx)
            require(rest.rx >= 0 && rest.tx >= 0)
            committed[id] = TrafficTotals()
            if (rest == TrafficTotals()) pending.remove(id) else pending[id] = rest
        }
    }
    fun clear(ids: Collection<Long>, store: () -> Unit) {
        val cut = pendingFor(ids)
        store()
        acknowledgeClear(cut)
    }
}
