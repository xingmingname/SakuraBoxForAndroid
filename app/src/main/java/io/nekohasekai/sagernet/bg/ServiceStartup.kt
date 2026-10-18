package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** Confined to the service's main dispatcher, including cancellation. */
class ServiceStartup(private val scope: CoroutineScope) {
    var job: Job? = null
        private set

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        check(job?.isActive != true) { "Service startup is already running" }
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (job === currentCoroutineContext()[Job]) job = null
            }
        }
        // Publish before starting: Main.immediate may run and finish inline.
        job = next
        next.start()
    }

    suspend fun cancelAndJoin() {
        val pending = job ?: return
        pending.cancelAndJoin()
        if (job === pending) job = null
    }
}
