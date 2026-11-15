package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test

class TrafficLedgerTest {
    private class Store {
        val rows = mutableMapOf<Long, TrafficTotals>()
        var writes = 0
        var fail = false
        fun commit(deltas: Map<Long, TrafficTotals>): Map<Long, TrafficTotals> {
            if (fail) error("transaction rollback")
            val next = rows.toMutableMap()
            deltas.forEach { (id, delta) -> next[id] = (next[id] ?: TrafficTotals()) + delta }
            rows.clear(); rows.putAll(next); writes++
            return next.filterKeys { it in deltas }
        }
    }
    @Test fun checkpointsRecoverLargeHistoryWithoutDuplicateCommit() {
        for (bytes in listOf(30_000_000_000L, 100_000_000_000L, 5L shl 40)) {
            val store = Store()
            val journal = TrafficJournal()
            val ledger = TrafficLedger(mapOf("a" to setOf(1)), "bypass")
            ledger.sample(mapOf("a" to TrafficTotals(bytes, bytes / 2)), journal::credit)
            journal.checkpoint(store::commit)
            journal.checkpoint(store::commit)
            assertEquals(1, store.writes)
            assertEquals(bytes, store.rows[1]!!.rx)
            val restored = TrafficJournal().apply { load(store.rows) }
            val restarted = TrafficLedger(mapOf("a" to setOf(1)), "bypass")
            restarted.sample(mapOf("a" to TrafficTotals(512, 256)), restored::credit)
            restored.checkpoint(store::commit)
            assertEquals(bytes + 512, store.rows[1]!!.rx)
            assertEquals(512L, restarted.session.rx)
        }
    }
    @Test fun failedTransactionRetainsDeltaThenRetryAddsItExactlyOnce() {
        val store = Store()
        val journal = TrafficJournal()
        journal.credit(1, TrafficTotals(30_000_000_000, 5))
        store.fail = true
        try { journal.checkpoint(store::commit); fail() } catch (_: IllegalStateException) {}
        assertTrue(journal.hasPending())
        journal.credit(1, TrafficTotals(1024, 8))
        store.fail = false
        journal.checkpoint(store::commit)
        journal.checkpoint(store::commit)
        assertEquals(TrafficTotals(30_000_001_024, 13), store.rows[1])
        assertFalse(journal.hasPending())
    }
    @Test fun finalSampleAndRepeatedStopDoNotLoseTailOrDoubleCount() {
        val journal = TrafficJournal()
        val store = Store()
        val ledger = TrafficLedger(mapOf("a" to setOf(1)), "bypass")
        ledger.sample(mapOf("a" to TrafficTotals(1024)), journal::credit)
        ledger.sample(mapOf("a" to TrafficTotals(3072)), journal::credit)
        journal.checkpoint(store::commit)
        ledger.sample(mapOf("a" to TrafficTotals(3072)), journal::credit)
        journal.checkpoint(store::commit)
        assertEquals(3072L, store.rows[1]!!.rx)
        assertEquals(1, store.writes)
    }
    @Test fun clearFailurePreservesPendingAndSuccessfulClearCreatesBoundary() {
        val journal = TrafficJournal()
        val store = Store()
        val ledger = TrafficLedger(mapOf("a" to setOf(1), "b" to setOf(2)), "bypass")
        ledger.sample(mapOf("a" to TrafficTotals(100), "b" to TrafficTotals(200)), journal::credit)
        try { journal.clear(listOf(1)) { error("rollback") }; fail() } catch (_: IllegalStateException) {}
        assertEquals(100L, journal.total(1).rx)
        journal.clear(listOf(1)) { store.rows[1] = TrafficTotals() }
        journal.checkpoint(store::commit)
        assertEquals(0L, store.rows[1]!!.rx)
        assertEquals(200L, store.rows[2]!!.rx)
        ledger.sample(mapOf("a" to TrafficTotals(125)), journal::credit)
        journal.checkpoint(store::commit)
        assertEquals(25L, store.rows[1]!!.rx)
        assertEquals(325L, ledger.session.rx) // clearing node history does not reset session
    }
    @Test fun displayedUncheckpointedWindowIsExplicitAndPersistedBaseSurvivesCrash() {
        val journal = TrafficJournal()
        val store = Store()
        journal.credit(1, TrafficTotals(30_000_000_000))
        journal.checkpoint(store::commit)
        journal.credit(1, TrafficTotals(1234))
        val restored = TrafficJournal().apply { load(store.rows) }
        assertEquals(30_000_000_000L, restored.total(1).rx)
        assertEquals(30_000_001_234L, journal.total(1).rx)
    }
    @Test fun networkAndActivityChangesDoNotResetCounterEpoch() {
        val ledger = TrafficLedger(mapOf("a" to setOf(1)), "bypass")
        val journal = TrafficJournal()
        listOf(1024L, 2048L, 2048L, 4096L).forEach {
            ledger.sample(mapOf("a" to TrafficTotals(it)), journal::credit)
        }
        assertEquals(4096L, journal.total(1).rx)
        assertEquals(4096L, ledger.session.rx)
    }
    @Test fun sharedComponentsAggregateUniqueTagsIndependentOfIterationOrder() {
        val owners = linkedMapOf("chainA" to setOf(1L, 2L, 3L), "chainB" to setOf(1L, 4L, 3L))
        for (mapping in listOf(owners, owners.entries.reversed().associate { it.toPair() })) {
            val journal = TrafficJournal()
            val ledger = TrafficLedger(mapping, "bypass")
            ledger.sample(linkedMapOf("chainA" to TrafficTotals(100), "chainB" to TrafficTotals(200),
                "bypass" to TrafficTotals(500)), journal::credit)
            assertEquals(300L, journal.total(1).rx)
            assertEquals(300L, journal.total(3).rx)
            assertEquals(100L, journal.total(2).rx)
            assertEquals(200L, journal.total(4).rx)
            assertEquals(300L, ledger.session.rx) // not 900 summed component bytes
        }
    }
    @Test fun repeatedNodeWithinChainIsCreditedOnceAndMultipleTagsAreSummed() {
        val journal = TrafficJournal()
        val ledger = TrafficLedger(mapOf("a" to listOf(1L, 2L, 1L).toSet(), "b" to setOf(1L)), "direct")
        ledger.sample(mapOf("a" to TrafficTotals(100), "b" to TrafficTotals(50)), journal::credit)
        assertEquals(150L, journal.total(1).rx)
        assertEquals(100L, journal.total(2).rx)
        assertEquals(150L, ledger.session.rx)
    }
    @Test fun selectorLongConnectionKeepsAdmissionOwnerAcrossRapidSwitches() {
        val journal = TrafficJournal()
        val ledger = TrafficLedger(mapOf("a" to setOf(1, 3), "b" to setOf(2, 3)), "direct")
        // Native bridge pins each connection counter to its admission-time root tag.
        ledger.sample(mapOf("a" to TrafficTotals(100)), journal::credit)
        ledger.sample(mapOf("a" to TrafficTotals(150), "b" to TrafficTotals(200)), journal::credit)
        ledger.sample(mapOf("a" to TrafficTotals(225), "b" to TrafficTotals(250)), journal::credit)
        assertEquals(225L, journal.total(1).rx)
        assertEquals(250L, journal.total(2).rx)
        assertEquals(475L, journal.total(3).rx)
        assertEquals(475L, ledger.session.rx)
    }
    @Test fun threeAndFourHopChainsShareMiddleWithoutInflatingSession() {
        val journal = TrafficJournal()
        val ledger = TrafficLedger(mapOf("three" to setOf(1, 2, 3), "four" to setOf(4, 2, 5, 6)), "bypass")
        ledger.sample(mapOf("three" to TrafficTotals(100), "four" to TrafficTotals(400)), journal::credit)
        assertEquals(500L, journal.total(2).rx)
        assertEquals(100L, journal.total(1).rx)
        assertEquals(400L, journal.total(6).rx)
        assertEquals(500L, ledger.session.rx)
    }

    @Test fun delayedResetAcknowledgementKeepsOnlyBytesAfterTheCut() {
        val journal = TrafficJournal()
        journal.load(mapOf(1L to TrafficTotals(30_000_000_000)))
        journal.credit(1, TrafficTotals(100))
        val cut = journal.pendingFor(listOf(1L))
        journal.credit(1, TrafficTotals(25))
        journal.acknowledgeClear(cut)
        assertEquals(25L, journal.total(1).rx)
    }

    @Test fun idleCountersDoNotCreditAnyOwnerOrCreatePendingWork() {
        val journal = TrafficJournal()
        val owners = (0 until 1000).associate { "tag-$it" to setOf(it.toLong(), 2000L) }
        val ledger = TrafficLedger(owners, "bypass")
        val values = owners.keys.associateWith { TrafficTotals(30_000_000_000L, 100_000_000_000L) }
        var calls = 0
        val credit: (Long, TrafficTotals) -> Unit = { id, delta -> calls++; journal.credit(id, delta) }
        ledger.sample(values, credit)
        journal.checkpoint { it }
        calls = 0
        repeat(20) { ledger.sample(values, credit) }
        assertEquals(0, calls)
        assertFalse(journal.hasPending())
        assertEquals(30_000_000_000_000L, ledger.session.rx)
        assertEquals(30_000_000_000_000L, journal.total(2000).rx)
    }

    @Test fun zeroFirstSampleAndCounterDropStillAdvanceBothDirectionBoundaries() {
        val journal = TrafficJournal()
        val ledger = TrafficLedger(mapOf("chain" to setOf(1L, 2L)), "bypass")
        var calls = 0
        val credit: (Long, TrafficTotals) -> Unit = { id, delta -> calls++; journal.credit(id, delta) }
        ledger.sample(mapOf("chain" to TrafficTotals()), credit)
        assertEquals(0, calls)
        ledger.sample(mapOf("chain" to TrafficTotals(100, 200)), credit)
        ledger.sample(mapOf("chain" to TrafficTotals(10, 20)), credit)
        assertEquals(2, calls)
        ledger.sample(mapOf("chain" to TrafficTotals(15, 20)), credit)
        ledger.sample(mapOf("chain" to TrafficTotals(15, 27)), credit)
        assertEquals(TrafficTotals(105, 207), journal.total(1))
        assertEquals(journal.total(1), journal.total(2))
        assertEquals(TrafficTotals(105, 207), ledger.session)
    }

    @Test fun sparseSamplingMatchesLegacyAccountingAcrossSharedChainsAndClear() {
        val owners = mapOf("a" to setOf(1L, 2L), "b" to setOf(2L, 3L), "bypass" to emptySet())
        val ledger = TrafficLedger(owners, "bypass")
        val journal = TrafficJournal()
        val previous = mutableMapOf<String, TrafficTotals>()
        val expected = mutableMapOf<Long, TrafficTotals>()
        var session = TrafficTotals()
        val random = java.util.Random(71)
        repeat(1000) { step ->
            val values = owners.keys.associateWith { tag ->
                val old = previous[tag] ?: TrafficTotals()
                if (step % 37 == 0) TrafficTotals() else TrafficTotals(old.rx + random.nextInt(3), old.tx + random.nextInt(3))
            }
            values.forEach { (tag, value) ->
                val old = previous[tag] ?: TrafficTotals()
                val delta = TrafficTotals(
                    if (value.rx >= old.rx) value.rx - old.rx else 0,
                    if (value.tx >= old.tx) value.tx - old.tx else 0)
                previous[tag] = value
                if (tag != "bypass") session += delta
                owners[tag].orEmpty().forEach { expected[it] = (expected[it] ?: TrafficTotals()) + delta }
            }
            ledger.sample(values, journal::credit)
            if (step % 113 == 0) {
                journal.clear(listOf(2L)) {}
                expected[2] = TrafficTotals()
            }
            assertEquals(session, ledger.session)
            for (id in 1L..3L) assertEquals(expected[id] ?: TrafficTotals(), journal.total(id))
        }
    }

}
