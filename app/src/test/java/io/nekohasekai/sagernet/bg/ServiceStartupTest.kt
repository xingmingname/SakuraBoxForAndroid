package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ServiceStartupTest {
    @Test fun stopCancelsStartupBeforeItCanPublishConnected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val startup = ServiceStartup(scope)
        var connected = false
        var cleaned = false
        val gate = CompletableDeferred<Unit>()
        startup.launch { try { gate.await(); connected = true } finally { cleaned = true } }
        assertNotNull(startup.job)
        startup.cancelAndJoin()
        gate.complete(Unit)
        assertNull(startup.job)
        assertFalse(connected)
        assertTrue(cleaned)
        scope.cancel()
    }

    @Test fun immediateCompletionDoesNotLeaveAStaleJob() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val startup = ServiceStartup(scope)
        repeat(5) { startup.launch { }; assertNull(startup.job) }
        scope.cancel()
    }

    @Test fun serviceDestructionCancelsPendingWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val startup = ServiceStartup(scope)
        startup.launch { awaitCancellation() }
        scope.cancel()
        startup.cancelAndJoin()
        assertNull(startup.job)
    }
}
