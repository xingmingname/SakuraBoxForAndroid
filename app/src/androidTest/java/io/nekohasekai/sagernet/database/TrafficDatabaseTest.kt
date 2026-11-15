package io.nekohasekai.sagernet.database

import android.test.InstrumentationTestCase
import androidx.room.Room
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class TrafficDatabaseTest : InstrumentationTestCase() {
    fun testStaleConfigConcurrentSubscriptionSortAndTrafficTransaction() {
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, SagerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.proxyDao()
            val original = ProxyEntity(groupId = 1)
            original.id = dao.addProxy(original)
            val stale = dao.getById(original.id)!!
            val amount = 30_000_000_000L
            dao.addTraffic(original.id, amount, 0)
            dao.updateProxy(stale.apply { userOrder = 5 })
            assertEquals(amount, dao.getById(original.id)!!.rx)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val writer = pool.submit { repeat(100) { dao.addTraffic(original.id, 1000, 100) } }
                val config = pool.submit {
                    repeat(100) { dao.updateProxy(listOf(stale.copy(userOrder = it.toLong()))) }
                }
                writer.get(30, TimeUnit.SECONDS); config.get(30, TimeUnit.SECONDS)
            } finally { pool.shutdownNow() }
            assertEquals(amount + 100_000, dao.getById(original.id)!!.rx)
            try {
                db.runInTransaction {
                    dao.addTraffic(original.id, 999, 999)
                    error("transaction rollback")
                }
            } catch (_: IllegalStateException) {}
            assertEquals(amount + 100_000, dao.getById(original.id)!!.rx)
            dao.clearTraffic(listOf(original.id))
            dao.updateProxy(stale)
            assertEquals(0L, dao.getById(original.id)!!.rx)
            dao.addTraffic(original.id, 5L shl 40, 100_000_000_000)
            assertEquals(5L shl 40, dao.getById(original.id)!!.rx)
        } finally { db.close() }
    }
}
