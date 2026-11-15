package io.nekohasekai.sagernet.bg

import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class ServiceCallbacksTest : InstrumentationTestCase() {
    private class Callback : ISagerNetServiceCallback.Stub() {
        override fun stateChanged(state: Int, profileName: String?, msg: String?) {}
        override fun missingPlugin(profileName: String?, pluginName: String?) {}
        override fun cbSpeedUpdate(stats: SpeedDisplayData?) {}
        override fun cbTrafficUpdate(stats: TrafficData?) {}
        override fun cbSelectorUpdate(id: Long) {}
    }

    fun testNativeCoreBuildName() {
        val lines = libcore.Libcore.versionBox().split('\n')
        assertEquals(3, lines.size)
        assertEquals("sing-box: 1.14.3-sakura", lines[0])
        assertTrue(lines[1].matches(Regex("go[^\\s]+@android/(arm|arm64|386|amd64)")))
        assertEquals("with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_sudoku,with_nakis", lines[2])
    }

    fun testCookieReplacementUnregisterAndClose() = runBlocking {
        val registry = BaseService.Binder()
        val callback = Callback()
        try {
            registry.registerCallback(callback, 2)
            registry.registerCallback(callback, 3)
            var hits = 0
            registry.broadcast(2) { hits++ }
            assertEquals(0, hits)
            registry.broadcast(3) { hits++ }
            assertEquals(1, hits)
            registry.unregisterCallback(callback)
            registry.broadcast { hits++ }
            assertEquals(1, hits)
            registry.close()
            registry.registerCallback(callback, 2)
            registry.broadcast { hits++ }
            assertEquals(1, hits)
        } finally { registry.close() }
    }

    fun testRemoteWorkDoesNotHoldRegistryLock() = runBlocking {
        val registry = BaseService.Binder()
        val callback = Callback()
        registry.registerCallback(callback, 2)
        try {
            registry.broadcast {
                val done = CountDownLatch(1)
                val worker = Thread {
                    try { registry.unregisterCallback(callback) }
                    finally { done.countDown() }
                }
                worker.start()
                assertTrue(done.await(2, TimeUnit.SECONDS))
                worker.join()
            }
            var count = 0
            registry.broadcast { count++ }
            assertEquals(0, count)
        } finally { registry.close() }
    }

    fun testConcurrentRegistrationAndSnapshotsRemainUsable() = runBlocking {
        val registry = BaseService.Binder()
        val failures = AtomicInteger()
        val workers = (0 until 3).map {
            Thread {
                try {
                    val cb = Callback()
                    repeat(200) { registry.registerCallback(cb, 2); registry.unregisterCallback(cb) }
                } catch (_: Throwable) { failures.incrementAndGet() }
            }.apply { start() }
        }
        try {
            repeat(200) { registry.broadcast {} }
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertEquals(0, failures.get())
        } finally { registry.close() }
    }
}
