package io.nekohasekai.sagernet.fmt

import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject

/** Run on a separate test installation, never against a user's live profile DB. */
@Suppress("DEPRECATION")
class ProductionConfigTest : InstrumentationTestCase() {
    fun testActualProtocolGeneratorAndJNIStart() = runBlocking(Dispatchers.IO) {
        val mode = DataStore.serviceMode
        val remote = DataStore.remoteDns
        val direct = DataStore.directDns
        val global = DataStore.globalCustomConfig
        try {
            DataStore.serviceMode = Key.MODE_PROXY
            DataStore.remoteDns = "https://1.1.1.1/dns-query"
            DataStore.directDns = "https://223.5.5.5/dns-query"
            DataStore.globalCustomConfig = """{"dns":{"rules+":[{"query_type":["TXT"],"action":"predefined","rcode":"NOERROR"},{"ip_version":6,"domain":["override.invalid"],"action":"predefined","rcode":"NOERROR"}]}}"""
            val hysteria = HysteriaBean().apply {
                initializeDefaultValues(); serverAddress = "127.0.0.1"
                authPayload = "synthetic-test"; sni = "example.invalid"
            }
            val reality = VMessBean().apply {
                initializeDefaultValues(); alterId = -1; serverAddress = "127.0.0.1"; serverPort = 443
                uuid = "00000000-0000-4000-8000-000000000001"
                encryption = "xtls-rprx-vision"; security = "tls"; sni = "example.invalid"
                realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
                realityShortId = "0123456789abcdef"
            }
            for (profile in listOf(
                ProxyEntity(type = ProxyEntity.TYPE_HYSTERIA, hysteriaBean = hysteria),
                ProxyEntity(type = ProxyEntity.TYPE_VMESS, vmessBean = reality),
            )) {
                val config = buildConfig(profile, forTest = false).config
                assertModernDNS(config)
                val servers = JSONObject(config).getJSONObject("dns").getJSONArray("servers")
                for (index in 0 until servers.length()) {
                    val server = servers.getJSONObject(index)
                    if (server.optString("tag") == "dns-direct") assertFalse(server.has("detour"))
                    if (server.optString("tag") == "dns-remote") assertEquals("proxy", server.getString("detour"))
                }
                val instance = Libcore.newSingBoxInstance(config, LocalResolverImpl)
                try { instance.start() } finally { instance.close() }
            }
        } finally {
            DataStore.serviceMode = mode; DataStore.remoteDns = remote
            DataStore.directDns = direct; DataStore.globalCustomConfig = global
        }
    }

    fun testProductionVPNConfigAndNativeDecoder() = runBlocking(Dispatchers.IO) {
        val mode = DataStore.serviceMode
        val sniff = DataStore.trafficSniffing
        val ipv6 = DataStore.ipv6Mode
        val resolve = DataStore.resolveDestination
        val remote = DataStore.remoteDns
        val direct = DataStore.directDns
        val fake = DataStore.enableFakeDns
        val global = DataStore.globalCustomConfig
        try {
            DataStore.serviceMode = Key.MODE_VPN
            DataStore.resolveDestination = true
            DataStore.remoteDns = "dhcp://auto"
            DataStore.directDns = "local"
            DataStore.globalCustomConfig = ""
            val bean = SOCKSBean().apply {
                initializeDefaultValues()
                serverAddress = "127.0.0.1"
                serverPort = 1080
                protocol = SOCKSBean.PROTOCOL_SOCKS5
            }
            val profile = ProxyEntity(type = ProxyEntity.TYPE_SOCKS, socksBean = bean)
            for (family in listOf(IPv6Mode.DISABLE, IPv6Mode.ENABLE, IPv6Mode.PREFER, IPv6Mode.ONLY)) {
                DataStore.ipv6Mode = family
                for (sniffMode in 0..2) {
                    DataStore.trafficSniffing = sniffMode
                    for (fakeDNS in listOf(false, true)) {
                        DataStore.enableFakeDns = fakeDNS
                        // forTest=false is essential: speed tests omit VPN inbounds.
                        val result = buildConfig(profile, forTest = false)
                        assertModernInbounds(result.config)
                        assertModernDNS(result.config)
                        val json = JSONObject(result.config)
                        val tun = json.getJSONArray("inbounds").getJSONObject(0)
                        assertFalse(tun.has("endpoint_independent_nat"))
                        assertEquals("endpoint_independent", tun.getString("udp_mapping"))
                        if (family == IPv6Mode.ONLY) {
                            val addresses = tun.getJSONArray("address")
                            assertEquals(1, addresses.length())
                            assertTrue(addresses.getString(0).contains(':'))
                        }
                        if (fakeDNS) {
                            val rules = json.getJSONObject("dns").getJSONArray("rules")
                            var checked = false
                            for (index in 0 until rules.length()) {
                                val rule = rules.getJSONObject(index)
                                if (rule.optString("server") == "dns-fake") {
                                    assertFalse(rule.has("strategy"))
                                    checked = true
                                }
                            }
                            assertTrue(checked)
                        }
                        Libcore.newSingBoxInstance(result.config, LocalResolverImpl).close()
                    }
                }
            }
            bean.customConfigJson = """{"inbounds":[{"type":"mixed","tag":"custom-in","listen":"127.0.0.1","sniff":true,"sniff_override_destination":true,"domain_strategy":"prefer_ipv6"}]}"""
            val overridden = buildConfig(profile, forTest = false)
            assertModernInbounds(overridden.config)
            assertEquals("sniff", JSONObject(overridden.config).getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
            Libcore.newSingBoxInstance(overridden.config, LocalResolverImpl).close()
        } finally {
            DataStore.serviceMode = mode; DataStore.trafficSniffing = sniff; DataStore.ipv6Mode = ipv6
            DataStore.resolveDestination = resolve; DataStore.remoteDns = remote; DataStore.directDns = direct
            DataStore.enableFakeDns = fake; DataStore.globalCustomConfig = global
        }
    }

    fun testFullCustomConfigPassesProductionMigration() {
        val bean = ConfigBean().apply {
            initializeDefaultValues()
            type = 0
            config = """{"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","sniff":true,"sniff_override_destination":true}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
        }
        val result = buildConfig(ProxyEntity(type = ProxyEntity.TYPE_CONFIG, configBean = bean))
        assertModernInbounds(result.config)
        Libcore.newSingBoxInstance(result.config, LocalResolverImpl).close()
        assertTrue(Libcore.versionBox().contains("1.14.3"))
    }

    fun testActualWireguardDefaultAndOverride() = runBlocking(Dispatchers.IO) {
        val mode=DataStore.serviceMode;val global=DataStore.globalCustomConfig
        val direct=DataStore.directDns;val remote=DataStore.remoteDns
        try {
            DataStore.serviceMode=Key.MODE_PROXY;DataStore.directDns="local";DataStore.remoteDns="local"
            val bean=io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean().apply {
                initializeDefaultValues();serverAddress="127.0.0.1";serverPort=51820;localAddress="10.0.0.2/32"
                privateKey="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
                peerPublicKey="CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg="
            }
            val profile=ProxyEntity(type=ProxyEntity.TYPE_WG,wgBean=bean)
            for (override in listOf(false,true)) {
                DataStore.globalCustomConfig=if(override) """{"route":{"final":"bypass"}}""" else ""
                val config=buildConfig(profile).config
                assertEquals(if(override) "bypass" else "proxy",JSONObject(config).getJSONObject("route").getString("final"))
                val instance=Libcore.newSingBoxInstance(config,LocalResolverImpl)
                try {instance.start()} finally {instance.close()}
            }
        } finally {DataStore.serviceMode=mode;DataStore.globalCustomConfig=global;DataStore.directDns=direct;DataStore.remoteDns=remote}
    }

    fun testPortableExportRejectsUnrepresentableSniffOverride() {
        val internal=Libcore.migrateConfig("""{"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","sniff":true,"sniff_override_destination":true}],"outbounds":[{"type":"direct","tag":"direct"}]}""")
        var rejected=false
        try { Libcore.exportPortableConfig(internal) } catch (e:Exception) { rejected=e.message.orEmpty().contains("_neko_sniff_override_rules") }
        assertTrue("Portable export must report destination-override behavior",rejected)
        val instance=Libcore.newSingBoxInstance(internal,LocalResolverImpl)
        try {instance.start()} finally {instance.close()}
        val portable=Libcore.exportPortableConfig("""{"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","sniff":true}],"outbounds":[{"type":"direct","tag":"direct"}]}""")
        assertFalse(portable.contains("_neko_"))
    }

    fun testOfficialAndInternalExportsStaySeparate() {
        val input = """{"inbounds":[{"type":"tun","tag":"custom-sfa-tun","address":["172.19.0.1/28"]},{"type":"mixed","tag":"local","listen":"127.0.0.1"}],"outbounds":[{"type":"direct","tag":"direct"}]}"""
        val bean = ConfigBean().apply { initializeDefaultValues(); type = 0; config = input }
        val profile = ProxyEntity(type = ProxyEntity.TYPE_CONFIG, configBean = bean)
        val before = buildConfig(profile).config
        val internal = profile.exportConfig()
        val official = profile.exportConfig(official = true)
        assertFalse(JSONObject(internal.first).getJSONArray("inbounds").getJSONObject(0).has("auto_route"))
        assertTrue(JSONObject(official.first).getJSONArray("inbounds").getJSONObject(0).getBoolean("auto_route"))
        assertFalse(JSONObject(official.first).getJSONArray("inbounds").getJSONObject(1).has("auto_route"))
        assertTrue(internal.first.contains("\n  \"inbounds\""))
        assertTrue(official.first.contains("\n  \"inbounds\""))
        assertTrue(internal.second.endsWith(".nekobox.json"))
        assertTrue(official.second.endsWith(".sing-box.json"))
        assertEquals(before, buildConfig(profile).config)
    }

    fun testGeneratedVPNAndProxyRuntimeAreIsolatedFromOfficialExport() {
        val mode = DataStore.serviceMode
        val sniff = DataStore.trafficSniffing
        val direct = DataStore.directDns
        val remote = DataStore.remoteDns
        val global = DataStore.globalCustomConfig
        try {
            DataStore.trafficSniffing = 0
            DataStore.directDns = "local"
            DataStore.remoteDns = "local"
            DataStore.globalCustomConfig = ""
            val bean = HysteriaBean().apply {
                initializeDefaultValues(); serverAddress = "127.0.0.1"
                authPayload = "synthetic-test"; sni = "example.invalid"
            }
            val profile = ProxyEntity(type = ProxyEntity.TYPE_HYSTERIA, hysteriaBean = bean)
            for (serviceMode in listOf(Key.MODE_VPN, Key.MODE_PROXY)) {
                DataStore.serviceMode = serviceMode
                val runtime = buildConfig(profile, forTest = false).config
                val internal = com.google.gson.JsonParser.parseString(runtime)
                val official = com.google.gson.JsonParser.parseString(profile.exportConfig(official = true).first)
                var tunCount = 0
                for (item in official.asJsonObject.getAsJsonArray("inbounds")) {
                    val inbound = item.asJsonObject
                    if (inbound["type"].asString == "tun") {
                        tunCount++
                        assertTrue(inbound["auto_route"].asBoolean)
                        inbound.remove("auto_route")
                    }
                }
                assertEquals(if (serviceMode == Key.MODE_VPN) 1 else 0, tunCount)
                assertEquals("Only official TUN auto_route may differ", internal, official)
                assertEquals(runtime, buildConfig(profile, forTest = false).config)
                assertEquals(runtime, Libcore.migrateConfig(runtime))
                Libcore.newSingBoxInstance(runtime, LocalResolverImpl).close()
            }
        } finally {
            DataStore.serviceMode = mode; DataStore.trafficSniffing = sniff
            DataStore.directDns = direct; DataStore.remoteDns = remote
            DataStore.globalCustomConfig = global
        }
    }

    private fun assertModernDNS(config: String) {
        val text=JSONObject(config).getJSONObject("dns").optJSONArray("rules")?.toString().orEmpty()
        assertFalse(text.contains("\"strategy\""));assertFalse(text.contains("\"outbound\""))
        assertFalse(config.contains("_neko_dns_policy"));assertFalse(config.contains("_neko_final_dns_policy"))
    }

    private fun assertModernInbounds(config: String) {
        val inbounds = JSONObject(config).getJSONArray("inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(index)
            for (field in listOf("sniff", "sniff_override_destination", "sniff_timeout", "domain_strategy", "udp_disable_domain_unmapping")) {
                assertFalse("legacy field reached generated config: $field", inbound.has(field))
            }
        }
    }
}
