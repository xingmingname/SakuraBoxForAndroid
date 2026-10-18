package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.hopPortsToSingboxList
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import moe.matsuri.nb4a.SingBoxOptions.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ProductionConfigTest {
    private fun fixture(name: String, outbound: SingBoxOption, address: String = "https://dns.example.invalid/dns-query") {
        outbound._hack_config_map["tag"] = "proxy"
        configureOutboundResolver(outbound, "prefer_ipv4", false)
        val root = MyOptions().apply {
            dns = DNSOptions().apply {
                servers = buildDnsServers("https://127.0.0.1/dns-query", address, "proxy")
                final_ = "dns-remote"
                strategy = "prefer_ipv6"
            }
            outbounds = listOf(outbound, Outbound().apply { type = "direct"; tag = "direct" })
            route = RouteOptions().apply { final_ = "proxy" }
        }
        val serialized = root.asMap()
        val servers = (serialized["dns"] as Map<*, *>)["servers"] as List<*>
        assertFalse((servers[1] as Map<*, *>).containsKey("detour"))
        assertEquals("proxy", (servers[2] as Map<*, *>)["detour"])
        System.getenv("NEKO_CONFIG_FIXTURES")?.let {
            File(it).mkdirs()
            File(it, "$name.json").writeText(Gson().toJson(serialized))
        }
    }

    @Test fun hysteria2ProductionBuilderAndAllDNSDialers() {
        val bean = HysteriaBean().apply {
            initializeDefaultValues()
            serverAddress = "127.0.0.1"
            authPayload = "synthetic-test-password"
            sni = "example.invalid"
            obfuscation = "synthetic-obfs"
        }
        for (ports in listOf("443", "443,8443", "443,10000-10010")) {
            bean.serverPorts = ports
            val outbound = buildSingBoxOutboundHysteriaBean(bean, false)
            val map = outbound.asMap()
            assertEquals("hysteria2", map["type"])
            assertEquals(false, (map["tls"] as Map<*, *>)["insecure"])
            if (ports.contains(',')) assertEquals(2, (map["server_ports"] as List<*>).size)
            for (scheme in listOf("udp", "tcp", "tls", "https", "quic", "h3")) {
                fixture("hysteria2-${ports.replace(',', '_')}-${scheme}", outbound, "$scheme://dns.example.invalid")
            }
        }
    }

    @Test fun realityProductionTLSAndTransport() {
        val bean = VMessBean().apply {
            initializeDefaultValues()
            alterId = -1
            serverAddress = "127.0.0.1"
            serverPort = 443
            uuid = "00000000-0000-4000-8000-000000000001"
            encryption = "xtls-rprx-vision"
            security = "tls"
            sni = "example.invalid"
            realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
            realityShortId = "0123456789abcdef"
        }
        val outbound = buildSingBoxOutboundStandardV2RayBean(bean, false)
        val tls = outbound.asMap()["tls"] as Map<*, *>
        assertEquals("chrome", (tls["utls"] as Map<*, *>)["fingerprint"])
        assertEquals(bean.realityPubKey, (tls["reality"] as Map<*, *>)["public_key"])
        assertEquals(false, tls["insecure"])
        fixture("vless-reality", outbound)
    }

    @Test fun directTestModeAndPlatformResolvers() {
        assertEquals(2, buildDnsServers("local", null, "proxy").size)
        for (value in listOf("local", "dhcp://auto", "dhcp://wlan0")) {
            val servers = buildDnsServers(value, value, "proxy")
            for (server in servers) {
                assertNull(server.detour)
                assertNull(server.domain_resolver)
            }
        }
    }

    @Test fun remainingProductionProtocolBuilders() {
        val vmess = VMessBean().apply {
            initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
            uuid = "00000000-0000-4000-8000-000000000001"
        }
        for (transport in listOf("tcp", "ws", "http", "grpc", "httpupgrade", "quic")) {
            vmess.type = transport
            vmess.security = if (transport == "quic") "tls" else "none"
            vmess.sni = "example.invalid"
            vmess.path = "/test"
            vmess.host = "example.invalid"
            fixture("vmess-$transport", buildSingBoxOutboundStandardV2RayBean(vmess, false))
        }
        val trojan = TrojanBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443; password = "synthetic-test" }
        fixture("trojan", buildSingBoxOutboundStandardV2RayBean(trojan, false))
        val http = HttpBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 8080 }
        fixture("http", buildSingBoxOutboundStandardV2RayBean(http, false))
        val socks = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 1080 }
        fixture("socks", buildSingBoxOutboundSocksBean(socks))
        val ss = ShadowsocksBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443; method = "aes-128-gcm"; password = "synthetic-test" }
        fixture("shadowsocks", buildSingBoxOutboundShadowsocksBean(ss))
    }

    @Test fun hoppingPortsRetainSinglesAndRejectInvalidInput() {
        assertEquals(listOf("443:443", "8443:8443", "1000:1002"), hopPortsToSingboxList("443,8443,1000-1002"))
        for (ports in listOf("", "443,", "0", "70000", "9000-8000", "a")) {
            assertThrows(Exception::class.java) { hopPortsToSingboxList(ports) }
        }
    }

    @Test fun chainForwardingAndExplicitCustomResolverRemainIntact() {
        val outbound = Outbound().apply { type = "socks" }
        configureOutboundResolver(outbound, "ipv6_only", false)
        assertEquals("ipv6_only", (outbound.asMap()["domain_resolver"] as Map<*, *>)["strategy"])
        setChainDetour(outbound, "next")
        assertFalse(outbound.asMap().containsKey("domain_resolver"))
        assertEquals("next", outbound.asMap()["detour"])
        val custom = CustomSingBoxOption("""{"type":"socks","domain_resolver":{"server":"user-dns","strategy":"prefer_ipv6"}}""")
        configureOutboundResolver(custom, "ipv4_only", false)
        setChainDetour(custom, "next")
        assertEquals("user-dns", (custom.asMap()["domain_resolver"] as Map<*, *>)["server"])
    }
}
