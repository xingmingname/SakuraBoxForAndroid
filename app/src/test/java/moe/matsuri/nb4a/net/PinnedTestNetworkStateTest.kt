package moe.matsuri.nb4a.net

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class PinnedTestNetworkStateTest {
    @Test fun failedBindingIsNotSwallowed() {
        val state = PinnedTestNetworkState()
        val error = IOException("synthetic binding failure")
        try {
            state.guard<Unit> { throw error }
            fail()
        } catch (actual: IOException) {
            assertSame(error, actual)
        }
    }

    @Test fun networkLossDuringBindingCannotReportSuccess() {
        val state = PinnedTestNetworkState()
        val error = IOException("synthetic network loss")
        try {
            state.guard { state.invalidate(error) }
            fail()
        } catch (actual: IOException) {
            assertSame(error, actual)
        }
    }

    @Test fun closedNetworkCannotBeReplacedOrUsed() {
        val state = PinnedTestNetworkState()
        val error = IOException("cancelled")
        assertTrue(state.invalidate(error))
        assertFalse(state.invalidate(IOException("replacement")))
        var called = false
        try {
            state.guard { called = true }
            fail()
        } catch (actual: IOException) { assertSame(error, actual) }
        assertFalse(called)
    }

    @Test fun concurrentFailurePublishesOnlyFirstAndInstancesAreIndependent() {
        val state = PinnedTestNetworkState()
        val other = PinnedTestNetworkState()
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val threads = (1..8).map {
            thread {
                start.await()
                if (state.invalidate(IOException("synthetic cancellation"))) winners.incrementAndGet()
            }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, winners.get())
        assertEquals(42, other.guard { 42 })
    }
}
