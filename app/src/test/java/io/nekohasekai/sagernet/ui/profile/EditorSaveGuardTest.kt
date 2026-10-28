package io.nekohasekai.sagernet.ui.profile

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class EditorSaveGuardTest {
    @Test fun discardDuringValidationPreventsLateCommit() {
        val guard = EditorSaveGuard()
        assertTrue(guard.begin())
        assertTrue(guard.discard())
        assertThrows(CancellationException::class.java) { guard.beginCommit() }
        guard.complete()
        assertFalse(guard.begin())
    }

    @Test fun alreadyCommittingIsNotReportedAsDiscarded() {
        val guard = EditorSaveGuard()
        assertTrue(guard.begin())
        guard.beginCommit()
        assertTrue(guard.isCommitting())
        assertFalse(guard.discard())
        guard.complete()
        assertTrue(guard.discard())
    }

    @Test fun failedValidationCanBeEditedSavedAgainOrDiscarded() {
        val guard = EditorSaveGuard()
        assertTrue(guard.begin())
        assertFalse(guard.begin())
        guard.complete()
        assertTrue(guard.begin())
        guard.complete()
        assertTrue(guard.discard())
        assertTrue(guard.discard())
    }

    @Test fun cancelledBlockingValidationCannotWriteWhenItEventuallyReturns() {
        val guard = EditorSaveGuard()
        val validating = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wrote = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val worker = thread {
            assertTrue(guard.begin())
            validating.countDown()
            release.await()
            try { guard.beginCommit(); wrote.set(true) }
            catch (_: CancellationException) { cancelled.set(true) }
        }
        assertTrue(validating.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(guard.discard())
        release.countDown()
        worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(cancelled.get())
        assertFalse(wrote.get())
    }

    @Test fun simultaneousCommitAndDiscardHaveExactlyOneWinner() {
        repeat(100) {
            val guard = EditorSaveGuard()
            assertTrue(guard.begin())
            val go = CountDownLatch(1)
            val committed = AtomicBoolean(false)
            val worker = thread {
                go.await()
                try { guard.beginCommit(); committed.set(true) } catch (_: CancellationException) { }
            }
            go.countDown()
            val discarded = guard.discard()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertNotEquals(discarded, committed.get())
        }
    }

    @Test fun actualJsonModelAllowsDiscardWhileBusyBeforeCommit() {
        val model = NodeJsonEditorModel()
        model.state.value = NodeJsonEditorModel.State(loaded = true, busy = true)
        val guard = NodeJsonEditorModel::class.java.getDeclaredField("saveGuard").apply { isAccessible = true }
            .get(model) as EditorSaveGuard
        assertTrue(guard.begin())
        assertFalse(model.committing)
        assertTrue(model.discard())
        assertThrows(CancellationException::class.java) { guard.beginCommit() }
    }

    @Test fun actualJsonModelRefusesDiscardOnlyAfterCommitBoundary() {
        val model = NodeJsonEditorModel()
        val guard = NodeJsonEditorModel::class.java.getDeclaredField("saveGuard").apply { isAccessible = true }
            .get(model) as EditorSaveGuard
        assertTrue(guard.begin())
        guard.beginCommit()
        assertTrue(model.committing)
        assertFalse(model.discard())
        guard.complete()
        assertTrue(model.discard())
    }
}
