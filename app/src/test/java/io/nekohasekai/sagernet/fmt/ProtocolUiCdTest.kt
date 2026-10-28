package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.canUseSingBox
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class ProtocolUiCdTest {
    private fun hy(version: Int) = HysteriaBean().apply {
        protocolVersion = version; initializeDefaultValues()
        protocol = HysteriaBean.PROTOCOL_UDP; serverAddress = "127.0.0.1"
        serverPorts = "443"; sni = "example.invalid"; authPayload = "synthetic"
        uploadMbps = 10; downloadMbps = 10
    }
    private fun quic() = listOf(hy(1), hy(2), TuicBean().apply {
        initializeDefaultValues(); protocolVersion = 5; serverAddress = "127.0.0.1"
        serverPort = 443; uuid = "00000000-0000-4000-8000-000000000001"
        token = "synthetic"; sni = "example.invalid"
    })
    private fun v2() = listOf(
        VMessBean().apply { alterId = -1; initializeDefaultValues() },
        VMessBean().apply { initializeDefaultValues() },
        TrojanBean().apply { initializeDefaultValues(); password = "synthetic" }
    ).onEach {
        it.serverAddress = "127.0.0.1"; it.serverPort = 443
        it.uuid = "00000000-0000-4000-8000-000000000001"
        it.type = "grpc"; it.security = "tls"; it.sni = "example.invalid"
    }
    private fun out(bean: AbstractBean): SingBoxOptions.SingBoxOption = when (bean) {
        is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean, false)
        is TuicBean -> buildSingBoxOutboundTuicBean(bean, false)
        is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean, false)
        is SSHBean -> buildSingBoxOutboundSSHBean(bean)
        is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
        else -> error("Unexpected fixture")
    }
    private fun final(name: String, bean: AbstractBean): com.google.gson.JsonObject {
        val node = out(bean)
        ProtocolOptions.applyTo(node, bean)
        node._hack_custom_config = bean.customOutboundJson
        node._hack_config_map["tag"] = "proxy"
        val root = SingBoxOptions.MyOptions().apply {
            outbounds = listOf(node)
            route = SingBoxOptions.RouteOptions().apply { final_ = "proxy" }
        }
        val json = com.google.gson.Gson().toJson(root.asMap())
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { File(it).mkdirs(); File(it, "cd-$name.json").writeText(json) }
        return JsonParser.parseString(json).asJsonObject["outbounds"].asJsonArray[0].asJsonObject
    }
    private fun staticECH(): String {
        // Synthetic public X25519 ECHConfigList, no private key or external server.
        val hex = "0041fe0d003d0000200020" + "07".repeat(32) +
            "000400010001400e7075626c69632e6578616d706c650000"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return "-----BEGIN ECH CONFIGS-----\n" + Base64.getEncoder().encodeToString(bytes) + "\n-----END ECH CONFIGS-----"
    }
    private fun syntheticPrivateKey(): String {
        // Ephemeral PKCS8 key for core and export fixtures.
        val encoded = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair().private.encoded
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(encoded) + "\n-----END PRIVATE KEY-----"
    }
    @Test fun nativeHY2UsesH3WithoutDiscardingStoredAlpnAndOthersKeepAlpn() {
        val b = hy(2).apply { alpn = "legacy-custom" }
        val stored = KryoConverters.serialize(b)
        assertFalse(ProtocolOptions.supportsCustomAlpn(b))
        assertEquals("h3", ProtocolOptions.get(final("hy2", b), "tls.alpn")!!.asJsonArray[0].asString)
        assertArrayEquals(stored, KryoConverters.serialize(b))
        b.protocolOptionsJson = """{"tls":{"alpn":["advanced-custom"]}}"""
        assertEquals("h3", ProtocolOptions.get(final("hy2-legacy-alpn", b), "tls.alpn")!!.asJsonArray[0].asString)
        assertTrue(b.protocolOptionsJson.contains("advanced-custom"))
        val h1 = hy(1).apply { alpn = "custom-h1" }
        assertTrue(ProtocolOptions.supportsCustomAlpn(h1))
        assertEquals("custom-h1", ProtocolOptions.get(final("hy1-alpn", h1), "tls.alpn")!!.asJsonArray[0].asString)
        b.protocol = HysteriaBean.PROTOCOL_FAKETCP
        assertFalse(ProtocolOptions.supportsCustomAlpn(b))
        assertTrue(b.canUseSingBox())
        assertEquals("h3", ProtocolOptions.get(final("hy2-retained-faketcp-alpn", b), "tls.alpn")!!.asJsonArray[0].asString)
        assertEquals("legacy-custom", b.alpn)
        h1.protocol = HysteriaBean.PROTOCOL_FAKETCP
        assertTrue(ProtocolOptions.supportsCustomAlpn(h1))
        assertFalse(h1.canUseSingBox())
        for (node in v2()) {
            node.alpn = "h2"
            assertTrue(ProtocolOptions.supportsCustomAlpn(node))
            assertEquals("h2", ProtocolOptions.get(final("alpn-" + ProtocolOptions.protocol(node), node), "tls.alpn")!!.asJsonArray[0].asString)
        }
    }
    @Test fun dynamicQuicEchDoesNotTriggerClientSidePreflightRejection() {
        for (b in quic()) {
            b.protocolOptionsJson = """{"tls":{"ech":{"enabled":true,"query_server_name":"query.invalid"}}}"""
            val stored = KryoConverters.serialize(b)
            val generated = final("quic-ech-deferred-"+ProtocolOptions.protocol(b), b)
            assertTrue(ProtocolOptions.get(generated,"tls.ech.enabled")!!.asBoolean)
            assertArrayEquals(stored, KryoConverters.serialize(b))
        }
    }

    @Test fun staticQuicEchSurvivesCloneAndFinalConfigWhileDynamicNameIsOmitted() {
        for (source in quic()) {
            val root = ProtocolOptions.parse("""{"tls":{"ech":{"enabled":true,"query_server_name":"retained.invalid"}}}""")
            root["tls"].asJsonObject["ech"].asJsonObject.addProperty("config", staticECH())
            source.protocolOptionsJson = root.toString()
            val b = source.clone()
            val generated = final("static-ech-" + ProtocolOptions.protocol(b), b)
            assertEquals(staticECH(), ProtocolOptions.get(generated, "tls.ech.config")!!.asString)
            assertNull(ProtocolOptions.get(generated, "tls.ech.query_server_name"))
            assertTrue(b.protocolOptionsJson.contains("retained.invalid"))
        }
    }
    @Test fun staticEchTextIsPreservedForNativeValidation() {
        for (bad in listOf("", "not-pem", "-----BEGIN ECH CONFIGS-----\nAAAA\n-----END ECH CONFIGS-----")) {
            val b = hy(2)
            val root = ProtocolOptions.parse("""{"tls":{"ech":{"enabled":true}}}""")
            root["tls"].asJsonObject["ech"].asJsonObject.addProperty("config", bad)
            b.protocolOptionsJson = root.toString()
            val generated=final("quic-ech-text",b)
            assertEquals(bad,ProtocolOptions.get(generated,"tls.ech.config")!!.asString)
            assertEquals(root.toString(),b.clone().protocolOptionsJson)
        }
    }

    @Test fun tcpDynamicEchAndExplicitOverridesKeepTheirExistingPriority() {
        val tcp = v2()[0].apply { enableECH = true; protocolOptionsJson = """{"tls":{"ech":{"query_server_name":"tcp.invalid"}}}""" }
        assertTrue(ProtocolOptions.editableField(tcp, "tls.ech.query_server_name"))
        assertEquals("tcp.invalid", ProtocolOptions.get(final("tcp-dynamic-ech", tcp), "tls.ech.query_server_name")!!.asString)
        val b = hy(2).apply {
            jsonMode = NodeJson.LEGACY
            protocolOptionsJson = """{"tls":{"ech":{"enabled":true,"query_server_name":"stored.invalid"}}}"""
            customOutboundJson = """{"tls":{"ech":{"enabled":false}}}"""
        }
        assertFalse(ProtocolOptions.get(final("ech-explicit-disabled", b), "tls.ech.enabled")!!.asBoolean)
        b.customOutboundJson = """{"tls":{"alpn":["manual"],"ech":{"enabled":false}}}"""
        assertEquals("manual", ProtocolOptions.get(final("manual-alpn-priority", b), "tls.alpn")!!.asJsonArray[0].asString)
    }
    @Test fun grpcLiteKeepsRealTimeoutsButOmitsUnsupportedKeepaliveForAllThreeProtocols() {
        for (b in v2()) {
            b.protocolOptionsJson = """{"transport":{"idle_timeout":"30s","ping_timeout":"9s","permit_without_stream":true}}"""
            assertFalse(ProtocolOptions.editableField(b, "transport.permit_without_stream"))
            val generated = final("grpc-" + ProtocolOptions.protocol(b), b)
            assertNull(ProtocolOptions.get(generated, "transport.permit_without_stream"))
            assertEquals("30s", ProtocolOptions.get(generated, "transport.idle_timeout")!!.asString)
            assertEquals("9s", ProtocolOptions.get(generated, "transport.ping_timeout")!!.asString)
            assertTrue(b.clone().protocolOptionsJson.contains("permit_without_stream"))
        }
    }
    @Test fun httpStaleTransportAndMuxAreUnavailableWithoutAffectingRealHttpFields() {
        for (type in listOf("tcp", "ws", "http", "grpc", "httpupgrade")) {
            val b = HttpBean().apply {
                initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 8080
                this.type = type; enableMux = true; host = "old.invalid"; path = "/old"
                username = "synthetic"; password = "synthetic"
                protocolOptionsJson = """{"path":"/connect","headers":{"X-Synthetic":"yes"},"multiplex":{"enabled":true},"transport":{"type":"ws","path":"/ignored"}}"""
            }
            assertFalse(ProtocolOptions.supportsSharedTransport(b))
            val generated = final("http-" + type, b)
            assertNull(generated["transport"]); assertNull(generated["multiplex"])
            assertEquals("/connect", generated["path"].asString)
            assertEquals("yes", ProtocolOptions.get(generated, "headers.X-Synthetic")!!.asString)
            assertEquals("synthetic", generated["username"].asString)
            assertEquals(type, (b.clone() as HttpBean).type)
            assertTrue(b.protocolOptionsJson.contains("ignored"))
        }
        for (b in v2()) {
            assertTrue(ProtocolOptions.supportsSharedTransport(b))
            for (type in listOf("ws", "http", "grpc", "httpupgrade")) {
                b.type = type; b.protocolOptionsJson = ""
                assertEquals(type, ProtocolOptions.get(final(ProtocolOptions.protocol(b) + "-" + type, b), "transport.type")!!.asString)
            }
        }
    }
    @Test fun sshAllSixAuthTransitionsAndBlobRoundTripsUseOnlySelectedAuthentication() {
        val transitions = listOf(1 to 0, 2 to 0, 1 to 2, 2 to 1, 0 to 1, 0 to 2)
        for ((from, to) in transitions) {
            val b = SSHBean().apply {
                initializeDefaultValues(); authType = from; serverAddress = "127.0.0.1"
                password = "synthetic-password"; privateKey = syntheticPrivateKey(); privateKeyPassphrase = ""
            }
            b.authType = to
            fun assertMode(map: com.google.gson.JsonObject) {
                assertEquals(to == 1, map.has("password"))
                assertEquals(to == 2, map.has("private_key"))
                assertEquals(to == 2, map.has("private_key_passphrase"))
            }
            assertMode(final("ssh-$from-$to", b))
            assertMode(final("ssh-$from-$to-reopened", b.clone()))
            assertEquals("synthetic-password", b.password) // Generating a config never clears inactive in-memory data.
        }
        val b = SSHBean().apply { initializeDefaultValues(); authType = 99 }
        assertThrows(IllegalStateException::class.java) { out(b) }
    }
    @Test fun hy1MtuMigrationUsesPreviousEffectivePriorityAndOnlyOneFutureWriter() {
        for (old in listOf(false, true)) for (advanced in listOf(false, true)) {
            val b = hy(1).apply {
                disableMtuDiscovery = old
                protocolOptionsJson = """{"disable_path_mtu_discovery":$advanced,"connect_timeout":"4s"}"""
            }
            assertFalse(ProtocolOptions.editableField(b, "disable_path_mtu_discovery"))
            assertEquals(advanced, final("hy1-before-$old-$advanced", b)["disable_path_mtu_discovery"].asBoolean)
            val migrated = b.clone()
            ProtocolOptions.migrateEditorOptions(migrated)
            assertEquals(advanced, migrated.disableMtuDiscovery)
            assertNull(ProtocolOptions.get(ProtocolOptions.parse(migrated.protocolOptionsJson), "disable_path_mtu_discovery"))
            assertEquals("4s", ProtocolOptions.get(ProtocolOptions.parse(migrated.protocolOptionsJson), "connect_timeout")!!.asString)
            val serialized = KryoConverters.serialize(migrated)
            ProtocolOptions.migrateEditorOptions(migrated)
            assertArrayEquals(serialized, KryoConverters.serialize(migrated))
            migrated.disableMtuDiscovery = !advanced
            assertEquals(!advanced, final("hy1-after-$old-$advanced", migrated.clone())["disable_path_mtu_discovery"].asBoolean)
            assertTrue(b.protocolOptionsJson.contains("disable_path_mtu_discovery"))
        }
        assertTrue(ProtocolOptions.editableField(hy(2), "disable_path_mtu_discovery"))
    }
    @Test fun unaffectedAnyTlsAndLegacyInvalidEditorDataRemainReadable() {
        val any = AnyTLSBean().apply {
            initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
            password = "synthetic"; sni = "example.invalid"; alpn = "h2"
        }
        assertTrue(ProtocolOptions.supportsCustomAlpn(any))
        assertEquals("h2", ProtocolOptions.get(final("anytls", any), "tls.alpn")!!.asJsonArray[0].asString)
        val malformed = hy(1).apply { protocolOptionsJson = "{" }
        ProtocolOptions.migrateEditorOptions(malformed)
        assertEquals("{", malformed.protocolOptionsJson)
        val external = hy(1).apply {
            protocol = HysteriaBean.PROTOCOL_FAKETCP; protocolOptionsJson = """{"disable_path_mtu_discovery":true}"""
        }
        val stored = external.protocolOptionsJson
        ProtocolOptions.migrateEditorOptions(external)
        assertEquals(stored, external.protocolOptionsJson)
    }
    @Test fun legacyHiddenValuesSurviveUniversalImportCopyAndBinarySave() {
        val nodes: List<AbstractBean> = quic() + listOf(HttpBean().apply { initializeDefaultValues(); type = "ws"; enableMux = true })
        for (b in nodes) {
            b.protocolOptionsJson = """{"tls":{"ech":{"query_server_name":"saved.invalid"}},"transport":{"permit_without_stream":true}}"""
            val copy = parseUniversal(b.toUniversalLink()).clone()
            assertEquals(b.protocolOptionsJson, copy.protocolOptionsJson)
            assertEquals(ProtocolOptions.protocol(b), ProtocolOptions.protocol(copy))
        }
    }
}
