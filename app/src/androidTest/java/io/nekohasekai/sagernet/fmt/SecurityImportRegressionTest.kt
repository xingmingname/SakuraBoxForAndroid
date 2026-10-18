package io.nekohasekai.sagernet.fmt

import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.ImportLimits

/** Parse-only synthetic inputs. Does not import into or reset the user's DB.
 * Run only in an isolated Android test installation. */
@Suppress("DEPRECATION")
class SecurityImportRegressionTest : InstrumentationTestCase() {
    fun testNormalJsonYamlIniAndLinksAfterFailures() = runBlocking(Dispatchers.Default) {
        val json = """{"outbounds":[{"type":"socks","tag":"synthetic","server":"127.0.0.1","server_port":1080}]}"""
        assertEquals(1, RawUpdater.parseRaw(json)!!.size)
        assertEquals(1, RawUpdater.parseRaw("proxies:\n - {name: synthetic, type: socks5, server: 127.0.0.1, port: 1080}")!!.size)
        assertEquals(1, RawUpdater.parseRaw("""[Interface]
PrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Address=10.0.0.2/32
[Peer]
PublicKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Endpoint=127.0.0.1:51820""")!!.size)
        try {
            RawUpdater.parseRaw("[".repeat(129) + "]".repeat(129))
            fail("excessive depth accepted")
        } catch (_: ImportLimits.InputLimitException) { }
        assertEquals(1, RawUpdater.parseRaw("socks://127.0.0.1:1080#synthetic")!!.size)
        assertEquals(1, RawUpdater.parseRaw(json)!!.size)
    }

    fun testLargeSubscriptionAndNodeQuota() = runBlocking(Dispatchers.Default) {
        val node = """{"type":"socks","server":"127.0.0.1","server_port":1080}"""
        val normal = "{\"outbounds\":[" + List(10000) { node }.joinToString(",") + "]}"
        assertEquals(10000, RawUpdater.parseRaw(normal)!!.size)
        val over = "{\"outbounds\":[" + List(50001) { "{}" }.joinToString(",") + "]}"
        try { RawUpdater.parseRaw(over); fail("node quota accepted") }
        catch (_: ImportLimits.InputLimitException) { }
        assertEquals(1, RawUpdater.parseRaw("socks://127.0.0.1:1080#after-quota")!!.size)
    }
}
