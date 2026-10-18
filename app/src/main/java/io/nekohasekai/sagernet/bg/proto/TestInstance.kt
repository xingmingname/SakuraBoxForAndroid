package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import libcore.Libcore
import moe.matsuri.nb4a.net.TestNetworkContext
import moe.matsuri.nb4a.net.requireTestPluginMappings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    @Volatile private var testNetwork: TestNetworkContext? = null

    suspend fun doTest(): Int {
        return suspendCancellableCoroutine { c ->
            val completed = AtomicBoolean(false)
            val workerRef = AtomicReference<Job?>()
            fun interrupt() {
                workerRef.get()?.cancel()
                // Do not close shared mainInstance or the main VPN controller.
                runOnDefaultDispatcher { if (isInitialized()) box.close() }
            }
            fun fail(error: Throwable) {
                if (completed.compareAndSet(false, true)) c.resumeWithException(error)
                interrupt()
            }
            processes = GuardedProcessPool {
                fail(it)
            }
            c.invokeOnCancellation {
                completed.set(true)
                testNetwork?.close()
                interrupt()
            }
            val worker = runOnDefaultDispatcher {
                use {
                    try {
                        coroutineContext.ensureActive()
                        testNetwork = TestNetworkContext.acquire { fail(it) }
                        coroutineContext.ensureActive()
                        init()
                        coroutineContext.ensureActive()
                        testNetwork!!.checkNetwork()
                        launch()
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            delay(500)
                        }
                        coroutineContext.ensureActive()
                        testNetwork!!.checkNetwork()
                        val latency = Libcore.urlTest(box, link, timeout)
                        testNetwork!!.checkNetwork()
                        if (completed.compareAndSet(false, true)) c.resume(latency)
                    } catch (e: Exception) {
                        fail(e)
                    }
                }
            }
            workerRef.set(worker)
            if (!c.isActive) worker.cancel()
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
        requireTestPluginMappings(config.config, config.externalIndex.flatMap { (chain) ->
            chain.values.map { it.requireBean().let { bean -> bean.finalAddress to bean.finalPort } }
        })
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        val network = checkNotNull(testNetwork) { "URL Test network was not acquired" }
        network.checkNetwork()
        box = Libcore.newTestSingBoxInstance(config.config, network.resolver, network)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            testNetwork?.close()
        }
    }

}
