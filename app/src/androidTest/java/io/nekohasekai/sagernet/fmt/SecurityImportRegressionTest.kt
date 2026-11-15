package io.nekohasekai.sagernet.fmt

import android.test.InstrumentationTestCase
import androidx.room.Room
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.group.SubscriptionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.ImportLimits

/** Synthetic inputs and in-memory storage for an isolated Android test installation. */
@Suppress("DEPRECATION")
class SecurityImportRegressionTest : InstrumentationTestCase() {
    fun testNormalJsonYamlIniAndLinksAfterFailures() = runBlocking(Dispatchers.Default) {
        val json = """{"outbounds":[{"type":"socks","tag":"synthetic","server":"127.0.0.1","server_port":1080}]}"""
        assertEquals(1, SubscriptionParser.parseRaw(json)!!.size)
        assertEquals(1, SubscriptionParser.parseRaw("proxies:\n - {name: synthetic, type: socks5, server: 127.0.0.1, port: 1080}")!!.size)
        assertEquals(1, SubscriptionParser.parseRaw(wireGuardIni)!!.size)
        try {
            SubscriptionParser.parseRaw("[".repeat(129) + "]".repeat(129))
            fail("excessive depth accepted")
        } catch (_: ImportLimits.InputLimitException) { }
        assertEquals(1, SubscriptionParser.parseRaw("socks://127.0.0.1:1080#synthetic")!!.size)
        assertEquals(1, SubscriptionParser.parseRaw(json)!!.size)
    }

    fun testWireGuardWithoutMtuPersistsDefault() = runBlocking(Dispatchers.IO) {
        val bean = requireNotNull(SubscriptionParser.parseRaw(wireGuardIni)).single() as WireGuardBean
        val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, SagerDatabase::class.java).build()
        try {
            val dao = database.proxyDao()
            val id = dao.addProxy(ProxyEntity(groupId = 1).apply { putBean(bean) })
            val restored = requireNotNull(dao.getById(id)).requireBean() as WireGuardBean
            assertEquals(1420, restored.mtu.toInt())
        } finally {
            database.close()
        }
    }

    private val wireGuardIni = """
        [Interface]
        PrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address=10.0.0.2/32
        [Peer]
        PublicKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Endpoint=127.0.0.1:51820
    """.trimIndent()

    fun testLargeSubscriptionAndNodeQuota() = runBlocking(Dispatchers.Default) {
        val node = """{"type":"socks","server":"127.0.0.1","server_port":1080}"""
        val normal = "{\"outbounds\":[" + List(10000) { node }.joinToString(",") + "]}"
        assertEquals(10000, SubscriptionParser.parseRaw(normal)!!.size)
        val over = "{\"outbounds\":[" + List(50001) { "{}" }.joinToString(",") + "]}"
        try { SubscriptionParser.parseRaw(over); fail("node quota accepted") }
        catch (_: ImportLimits.InputLimitException) { }
        assertEquals(1, SubscriptionParser.parseRaw("socks://127.0.0.1:1080#after-quota")!!.size)
    }
}
