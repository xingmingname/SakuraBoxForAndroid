package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HysteriaVersionTest {
    private fun bean(transport: Int) = HysteriaBean().apply {
        initializeDefaultValues(); protocolVersion = 1; protocol = transport
        serverAddress = "example.invalid"; serverPorts = "443,8443-8444"
        authPayload = "synthetic"; obfuscation = "synthetic-obfs"
        sni = "example.invalid"; hopInterval = 10
        protocolOptionsJson = """{"idle_timeout":"20s"}"""
    }
    private val transports = listOf(HysteriaBean.PROTOCOL_UDP, HysteriaBean.PROTOCOL_FAKETCP, HysteriaBean.PROTOCOL_WECHAT_VIDEO)

    @Test fun versionTwoAlwaysUsesNativeCoreWithoutErasingVersionOneTransport() {
        for (transport in transports) {
            var current = bean(transport)
            for (version in listOf(1, 2, 1, 2, 1)) {
                current.protocolVersion = version
                current = current.clone()
                val native = version == 2 || transport == HysteriaBean.PROTOCOL_UDP
                assertEquals(native, current.canUseSingBox())
                assertEquals(!native, ProxyEntity().apply { putBean(current) }.needExternal())
                assertEquals(if (version == 2) "hysteria2" else if (native) "hysteria" else null, ProtocolOptions.protocol(current))
                assertEquals(transport, current.protocol.toInt())
                assertEquals("example.invalid", current.sni)
                assertEquals("443,8443-8444", current.serverPorts)
            }
        }
    }

    @Test fun hiddenVersionOneTransportCannotChangeHysteriaTwoTlsAlpnHopOrObfs() {
        for (transport in transports) {
            val current = bean(transport).apply { protocolVersion = 2; alpn = "old-h1-alpn"; serverAddress = "127.0.0.1" }
            val before = KryoConverters.serialize(current).toList()
            val out = buildSingBoxOutboundHysteriaBean(current, false)
            ProtocolOptions.applyTo(out, current)
            val json = Gson().toJsonTree(out.asMap()).asJsonObject
            assertEquals("hysteria2", json["type"].asString)
            assertEquals("10s", json["hop_interval"].asString)
            assertEquals(listOf("443:443", "8443:8444"), json.getAsJsonArray("server_ports").map { it.asString })
            assertEquals(listOf("h3"), json.getAsJsonObject("tls").getAsJsonArray("alpn").map { it.asString })
            assertTrue(json.getAsJsonObject("tls")["enabled"].asBoolean)
            assertEquals("salamander", json.getAsJsonObject("obfs")["type"].asString)
            assertEquals("20s", json["idle_timeout"].asString)
            assertFalse(json.has("protocol"))
            assertEquals(before, KryoConverters.serialize(current).toList())
            System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
                File(directory).mkdirs()
                File(directory, "ui-cd-hysteria2-version-switch-$transport.json").writeText(
                    """{"outbounds":[$json]}""")
            }
        }
    }

    @Test fun rootUidBypassIsReservedForVersionOneFakeTcp() {
        for (transport in transports) {
            val current = bean(transport)
            for (version in listOf(1, 2, 1, 2)) {
                current.protocolVersion = version
                assertEquals(version == 1 && transport == HysteriaBean.PROTOCOL_FAKETCP, current.requiresRootBypass())
                assertEquals(transport, current.protocol.toInt())
            }
        }
    }

    @Test fun versionOneExternalTransportSupportAndStoredOptionsRemain() {
        for (transport in transports) {
            val current = bean(transport)
            assertEquals(transport == HysteriaBean.PROTOCOL_UDP, current.canUseSingBox())
            current.protocolVersion = 2
            assertFalse(ProtocolOptions.supportsCustomAlpn(current))
            current.protocolVersion = 1
            assertTrue(ProtocolOptions.supportsCustomAlpn(current))
            assertEquals(transport, current.protocol.toInt())
        }
    }
}
