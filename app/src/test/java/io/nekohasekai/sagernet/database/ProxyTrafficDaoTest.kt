package io.nekohasekai.sagernet.database

import org.junit.Assert.*
import org.junit.Test

class ProxyTrafficDaoTest {
    private class MemoryDao : ProxyEntity.Dao() {
        val rows = mutableMapOf<Long, ProxyEntity>()
        override fun getAll(): List<ProxyEntity> = throw UnsupportedOperationException()
        override fun getIdsByGroup(groupId: Long): List<Long> = throw UnsupportedOperationException()
        override fun getByGroup(groupId: Long): List<ProxyEntity> = throw UnsupportedOperationException()
        override fun getEntities(proxyIds: List<Long>): List<ProxyEntity> = throw UnsupportedOperationException()
        override fun countByGroup(groupId: Long): Long = throw UnsupportedOperationException()
        override fun nextOrder(groupId: Long): Long? = throw UnsupportedOperationException()
        override fun getById(proxyId: Long): ProxyEntity? = rows[proxyId]?.copy()
        override fun deleteById(proxyId: Long): Int = throw UnsupportedOperationException()
        override fun deleteByGroup(groupId: Long): Unit = throw UnsupportedOperationException()
        override fun deleteByGroup(groupId: LongArray): Unit = throw UnsupportedOperationException()
        override fun deleteProxy(proxy: ProxyEntity): Int = throw UnsupportedOperationException()
        override fun deleteProxy(proxies: List<ProxyEntity>): Int = throw UnsupportedOperationException()
        override fun updateProxyRow(proxy: ProxyEntity): Int = 1.also { rows[proxy.id] = proxy.copy() }
        override fun updateTestResult(id: Long, status: Int, ping: Int, error: String?): Int = throw UnsupportedOperationException()
        override fun updateTraffic(id: Long, rx: Long, tx: Long): Int = 1.also { rows[id]!!.apply { this.rx = rx; this.tx = tx } }
        override fun getTraffic(id: Long): ProxyTraffic? = rows[id]?.let { ProxyTraffic(it.id, it.rx, it.tx) }
        override fun getTraffic(ids: List<Long>): List<ProxyTraffic> = ids.mapNotNull { getTraffic(it) }
        override fun addTraffic(id: Long, rx: Long, tx: Long): Int = 1.also { rows[id]!!.apply { this.rx += rx; this.tx += tx } }
        override fun clearTraffic(ids: List<Long>): Int = ids.size.also { ids.forEach { rows[it]!!.apply { rx = 0; tx = 0 } } }
        override fun addProxy(proxy: ProxyEntity): Long = throw UnsupportedOperationException()
        override fun insert(proxies: List<ProxyEntity>): Unit = throw UnsupportedOperationException()
        override fun deleteAll(groupId: Long): Int = throw UnsupportedOperationException()
        override fun reset(): Unit = throw UnsupportedOperationException()
    }

    @Test fun staleConfigAndBatchSortNeverReplaceCommittedHistory() {
        for (amount in listOf(30_000_000_000L, 100_000_000_000L, 5L shl 40)) {
            val dao = MemoryDao()
            val stale = ProxyEntity(id = 1, userOrder = 1)
            dao.rows[1] = stale.copy()
            dao.addTraffic(1, amount, amount / 2)
            stale.userOrder = 9
            assertEquals(1, dao.updateProxy(stale))
            assertEquals(amount, dao.rows[1]!!.rx)
            val staleSubscription = ProxyEntity(id = 1, userOrder = 11)
            dao.addTraffic(1, 4096, 2048)
            dao.updateProxy(listOf(staleSubscription))
            assertEquals(amount + 4096, dao.rows[1]!!.rx)
            assertEquals(11L, dao.rows[1]!!.userOrder)
        }
    }

    @Test fun explicitResetCanDecreaseHistoryAndStaleEditorsCannotUndoIt() {
        val dao = MemoryDao()
        val stale = ProxyEntity(id = 1, rx = 30_000_000_000, tx = 42)
        dao.rows[1] = stale.copy()
        dao.clearTraffic(listOf(1))
        dao.updateProxy(stale)
        assertEquals(0L, dao.rows[1]!!.rx)
        dao.addTraffic(1, 100, 200)
        dao.updateProxy(ProxyEntity(id = 1))
        assertEquals(100L, dao.rows[1]!!.rx)
        assertEquals(200L, dao.rows[1]!!.tx)
    }
}
