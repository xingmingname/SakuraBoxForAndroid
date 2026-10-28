package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.importAnyTLSOutbound
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl

class ConnectionSecurityTest {
    private val key = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
    private val modes = ConnectionSecurity.Mode.entries

    private fun parseVless(link: String) = VMessBean().apply {
        initializeDefaultValues(); alterId = -1
        parseDuckSoft(link.replace("vless://", "https://").toHttpUrl())
        initializeDefaultValues()
    }

    private fun standard(kind: String): StandardV2RayBean = when (kind) {
        "http" -> HttpBean()
        "trojan" -> TrojanBean().apply { password = "synthetic" }
        else -> VMessBean().apply { alterId = if (kind == "vless") -1 else 0 }
    }.apply {
        initializeDefaultValues()
        serverAddress = "127.0.0.1"; serverPort = 443; sni = "example.invalid"
        uuid = "00000000-0000-4000-8000-000000000001"
        encryption = if (kind == "vless") "" else "auto"
        realityPubKey = key; realityShortId = "0123456789abcdef"
    }

    private fun any() = AnyTLSBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
        password = "synthetic"; sni = "example.invalid"
        protocolOptionsJson = """{"tls":{"reality":{"public_key":"$key","short_id":""}}}"""
    }

    private fun generated(bean: AbstractBean, fixture: String? = null): Map<String, Any?> {
        val out = when (bean) {
            is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean, false)
            is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
            else -> error("test protocol")
        }
        ProtocolOptions.applyTo(out, bean)
        out._hack_config_map["tag"] = "proxy"
        val serialized = out.asMap()
        if (fixture != null) System.getenv("NEKO_CONFIG_FIXTURES")?.let {
            File(it).mkdirs()
            val root = SingBoxOptions.MyOptions().apply { outbounds = listOf(out) }.asMap()
            File(it, "tls-reality-$fixture.json").writeText(Gson().toJson(root))
        }
        return serialized
    }

    @Test fun supportedModesFollowTheNativeProtocol() {
        for (kind in listOf("vless", "vmess", "trojan", "http")) assertEquals(modes, ConnectionSecurity.supported(standard(kind)))
        assertEquals(listOf(ConnectionSecurity.Mode.TLS, ConnectionSecurity.Mode.REALITY), ConnectionSecurity.supported(any()))
        for (bean in listOf(HysteriaBean(), TuicBean(), SOCKSBean())) assertTrue(ConnectionSecurity.supported(bean).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ConnectionSecurity.select(any(), ConnectionSecurity.Mode.NONE) }
    }

    @Test fun everyStandardProtocolSurvivesEveryModeTransitionAndClone() {
        for (kind in listOf("vless", "vmess", "trojan", "http")) for (from in modes) for (to in modes) {
            val bean = standard(kind)
            bean.alpn = "h2\nhttp/1.1"
            ConnectionSecurity.select(bean, from)
            val oldKey = bean.realityPubKey
            ConnectionSecurity.select(bean, to)
            val reopened = bean.clone() as StandardV2RayBean
            assertEquals(to, ConnectionSecurity.mode(reopened))
            assertEquals(oldKey, reopened.realityPubKey)
            assertEquals(bean.realityShortId, reopened.realityShortId)
            assertEquals(bean.sni, reopened.sni)
            assertEquals(bean.alpn, reopened.alpn)
            assertEquals(KryoConverters.serialize(reopened).toList(), KryoConverters.serialize(reopened.clone()).toList())
            val out = generated(reopened, "$kind-${from.value}-${to.value}")
            val tls = out["tls"] as? Map<*, *>
            if (to == ConnectionSecurity.Mode.NONE) assertNull(tls)
            else {
                assertEquals(true, tls!!["enabled"])
                assertEquals(bean.sni, tls["server_name"])
                if (to == ConnectionSecurity.Mode.REALITY) {
                    assertEquals(key, (tls["reality"] as Map<*, *>)["public_key"])
                    assertEquals(true, (tls["utls"] as Map<*, *>)["enabled"])
                } else assertNull(tls["reality"])
            }
        }
    }

    @Test fun noneRetainsInactiveTlsAndRealityValuesInTheBlob() {
        val bean = standard("vless").apply {
            certificates = "synthetic-stored-certificate"; allowInsecure = false
            enableECH = true; echConfig = "synthetic-stored-ech"; utlsFingerprint = "chrome"
            protocolOptionsJson = """{"tls":{"certificate_public_key_sha256":["saved-pin"],"max_version":"1.2"},"connect_timeout":"5s"}"""
        }
        ConnectionSecurity.select(bean, ConnectionSecurity.Mode.NONE)
        val copy = bean.clone() as StandardV2RayBean
        assertEquals(bean.certificates, copy.certificates)
        assertEquals(bean.echConfig, copy.echConfig)
        assertEquals(bean.utlsFingerprint, copy.utlsFingerprint)
        assertEquals(bean.protocolOptionsJson, copy.protocolOptionsJson)
        val out = generated(copy)
        assertNull(out["tls"])
        assertEquals("5s", out["connect_timeout"])
    }

    @Test fun inactiveTlsVerificationAndEchDoNotPolluteReality() {
        val bean = standard("vless").apply {
            security = "tls"; allowInsecure = true; certificates = "synthetic-inactive-ca"
            enableECH = true; echConfig = "synthetic-inactive-ech"
            protocolOptionsJson = """{"tls":{"disable_sni":true,"ech":{"enabled":true,"query_server_name":"example.invalid"},"certificate_public_key_sha256":["saved-pin"]}}"""
        }
        ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY)
        val before = bean.protocolOptionsJson
        val tls = generated(bean)["tls"] as Map<*, *>
        for (k in listOf("ech", "certificate", "certificate_public_key_sha256", "disable_sni")) assertNull(tls[k])
        assertEquals(false, tls["insecure"])
        assertEquals(before, bean.protocolOptionsJson)
        assertEquals("synthetic-inactive-ca", (bean.clone() as StandardV2RayBean).certificates)
    }

    @Test fun legacyVersionFourTlsAndRealityNodesReadWithoutReconfiguration() {
        for (reality in listOf(false, true)) {
            val bytes = ByteBufferOutput(2048, -1).apply {
                writeInt(4)
                writeString("127.0.0.1"); writeInt(443)
                writeString("00000000-0000-4000-8000-000000000001"); writeString(""); writeInt(-1)
                writeString("tcp"); writeString("tls"); writeString("example.invalid"); writeString("h2")
                writeString(""); writeBoolean(false); writeString("chrome")
                writeString(if (reality) key else ""); writeString("")
                writeBoolean(false); writeString(""); writeInt(0)
                writeBoolean(false); writeBoolean(false); writeInt(0); writeInt(1)
                writeInt(2); writeString("old"); writeString(""); writeString(""); writeString("")
            }.toBytes()
            val bean = KryoConverters.deserialize(VMessBean(), bytes)
            assertEquals(if (reality) ConnectionSecurity.Mode.REALITY else ConnectionSecurity.Mode.TLS, ConnectionSecurity.mode(bean))
            assertEquals("example.invalid", bean.sni)
            assertEquals(if (reality) key else "", (bean.clone() as VMessBean).realityPubKey)
        }
    }

    @Test fun realityVlessUriParametersRoundTripAndTlsDoesNotExportTheSavedKey() {
        val link = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?security=reality&pbk=$key&sid=0123456789abcdef&sni=example.invalid&fp=firefox&type=tcp"
        val bean = parseVless(link)
        assertEquals(ConnectionSecurity.Mode.REALITY, ConnectionSecurity.mode(bean))
        assertEquals("firefox", bean.utlsFingerprint)
        val imported = parseVless(bean.toUriVMessVLESSTrojan(false))
        assertEquals(key, imported.realityPubKey)
        assertEquals(bean.realityShortId, imported.realityShortId)
        assertEquals(bean.sni, imported.sni)
        ConnectionSecurity.select(bean, ConnectionSecurity.Mode.TLS)
        val tlsLink = bean.toUriVMessVLESSTrojan(false)
        assertTrue(tlsLink.contains("security=tls"))
        assertFalse(tlsLink.contains("pbk=")); assertFalse(tlsLink.contains("sid="))
        assertEquals(key, bean.realityPubKey)
        val missing = parseVless("vless://id@127.0.0.1:443?security=reality")
        assertEquals("", (generated(missing)["tls"] as Map<*, *>)["reality"].let { (it as Map<*, *>)["public_key"] })
    }

    @Test fun anyTlsImportedRealityRemainsEditableAndCanReturnToTls() {
        val bean = importAnyTLSOutbound("""{"type":"anytls","server":"127.0.0.1","server_port":443,"password":"synthetic","tls":{"enabled":true,"server_name":"example.invalid","reality":{"enabled":true,"public_key":"$key","short_id":""}}}""")!!
        for (mode in listOf(ConnectionSecurity.Mode.TLS, ConnectionSecurity.Mode.REALITY, ConnectionSecurity.Mode.TLS)) {
            ConnectionSecurity.select(bean, mode)
            val copy = bean.clone()
            assertEquals(mode, ConnectionSecurity.mode(copy))
            val tls = generated(copy, "anytls-${mode.value}")["tls"] as Map<*, *>
            assertEquals(true, tls["enabled"])
            assertEquals(mode == ConnectionSecurity.Mode.REALITY, tls["reality"] != null)
            assertEquals(key, ConnectionSecurity.publicKey(copy, ProtocolOptions.parse(copy.protocolOptionsJson)))
        }
    }

    @Test fun realityFieldsArePreservedForTheCoreToInterpret() {
        for (publicKey in listOf("", "synthetic", "$key=", key.dropLast(1), key))
            for (sid in listOf("", "0", "xyz", "001122334455667788", "ABCD")) {
                val bean = standard("vless")
                ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY)
                bean.realityPubKey = publicKey; bean.realityShortId = sid
                val before = KryoConverters.serialize(bean)
                val reality = (generated(bean)["tls"] as Map<*, *>)["reality"] as Map<*, *>
                assertEquals(publicKey, reality["public_key"])
                assertEquals(sid, reality["short_id"])
                assertArrayEquals(before, KryoConverters.serialize(bean))
            }
    }

    @Test fun tlsCertificateAndOtherAdvancedSettingsSurviveModeSwitches() {
        val bean = standard("vmess").apply {
            certificates = "synthetic-ca"
            protocolOptionsJson = """{"connect_timeout":"9s","tls":{"min_version":"1.2","handshake_timeout":"4s"}}"""
        }
        for (mode in listOf(ConnectionSecurity.Mode.REALITY, ConnectionSecurity.Mode.NONE, ConnectionSecurity.Mode.TLS)) {
            ConnectionSecurity.select(bean, mode)
            val copy = bean.clone() as StandardV2RayBean
            assertEquals("synthetic-ca", copy.certificates)
            assertEquals("9s", ProtocolOptions.get(ProtocolOptions.parse(copy.protocolOptionsJson), "connect_timeout")!!.asString)
        }
        val tls = generated(bean)["tls"] as Map<*, *>
        assertEquals("synthetic-ca", tls["certificate"])
        assertEquals("1.2", tls["min_version"])
        assertEquals("4s", tls["handshake_timeout"])
        assertNull(tls["reality"])
    }

    @Test fun uiVisibilityTracksModesWithoutDiscardingStoredFields() {
        val bean = standard("vless")
        for (mode in modes) {
            ConnectionSecurity.select(bean, mode)
            val root = ProtocolOptions.parse(bean.protocolOptionsJson)
            assertTrue(ConnectionSecurity.showsAdvancedField(bean, root, "connect_timeout"))
            assertEquals(mode != ConnectionSecurity.Mode.NONE, ConnectionSecurity.showsAdvancedField(bean, root, "tls.handshake_timeout"))
            for (p in listOf("tls.ech.query_server_name", "tls.certificate_public_key_sha256")) {
                assertEquals(mode == ConnectionSecurity.Mode.TLS, ConnectionSecurity.showsAdvancedField(bean, root, p))
            }
            assertEquals(mode != ConnectionSecurity.Mode.NONE, ConnectionSecurity.showsAdvancedField(bean, root, "tls.min_version"))
        }
    }

    @Test fun clearingAdvancedSettingsCannotChangeSecurityModeOrEraseItsKeys() {
        for (bean in listOf(standard("vless"), any())) for (mode in ConnectionSecurity.supported(bean)) {
            ConnectionSecurity.select(bean, mode)
            val root = ProtocolOptions.parse(bean.protocolOptionsJson).apply { addProperty("connect_timeout", "8s") }
            bean.protocolOptionsJson = ConnectionSecurity.clearAdvancedOptions(bean, root).toString()
            assertEquals(mode, ConnectionSecurity.mode(bean))
            assertNull(ProtocolOptions.get(ProtocolOptions.parse(bean.protocolOptionsJson), "connect_timeout"))
            assertEquals(key, ConnectionSecurity.publicKey(bean, ProtocolOptions.parse(bean.protocolOptionsJson)))
        }
    }

    @Test fun unrepresentableRealityUsesTheExistingLosslessUniversalFormat() {
        for (bean in listOf(standard("vmess"), standard("http"), any())) {
            if (bean is StandardV2RayBean) bean.security = "tls" // Legacy TLS + public key.
            else ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY)
            val entity = ProxyEntity().putBean(bean)
            assertFalse(entity.haveStandardLink())
            val link = entity.toStdLink()
            assertTrue(link.startsWith("sn://"))
            val restored = parseUniversal(link)
            assertEquals(ConnectionSecurity.Mode.REALITY, ConnectionSecurity.mode(restored))
            assertEquals(key, ConnectionSecurity.publicKey(restored, ProtocolOptions.parse(restored.protocolOptionsJson)))
        }
    }

    @Test fun sharedTransportsConstructWithTlsAndRealityWithoutChangingTheirFields() {
        for (kind in listOf("vless", "vmess", "trojan")) for (transport in listOf("ws", "http", "grpc", "httpupgrade")) {
            for (mode in listOf(ConnectionSecurity.Mode.TLS, ConnectionSecurity.Mode.REALITY)) {
                val bean = standard(kind).apply {
                    type = transport; host = "example.invalid"
                    path = if (transport == "grpc") "synthetic.service" else "/stream"
                }
                ConnectionSecurity.select(bean, mode)
                val copy = bean.clone() as StandardV2RayBean
                val out = generated(copy, "$kind-$transport-${mode.value}")
                assertEquals(transport, (out["transport"] as Map<*, *>)["type"])
                assertEquals(bean.path, copy.path)
                assertEquals(mode, ConnectionSecurity.mode(copy))
            }
        }
    }

    @Test fun malformedSavedAdvancedJsonRemainsVisibleAndIsNotSilentlyReplaced() {
        val bean = standard("vless").apply { security = "tls"; realityPubKey = ""; protocolOptionsJson = "not-json" }
        assertEquals(ConnectionSecurity.Mode.TLS, ConnectionSecurity.mode(bean))
        assertThrows(IllegalArgumentException::class.java) { ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY) }
        assertEquals("not-json", bean.protocolOptionsJson)
    }

    @Test fun realityRetainsVersionOptionsWithoutClientSideRejection() {
        val bean = standard("vless").apply { protocolOptionsJson = """{"tls":{"max_version":"1.2","curve_preferences":["X25519"]}}""" }
        ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY)
        val before = KryoConverters.serialize(bean)
        val tls = generated(bean)["tls"] as Map<*, *>
        assertEquals("1.2", tls["max_version"])
        assertEquals(listOf("X25519"), tls["curve_preferences"])
        assertArrayEquals(before, KryoConverters.serialize(bean))
    }

    @Test fun securityMenuValuesExistBeforeAndroidInflatesTheDropdown() {
        val app = if (File("src/main/AndroidManifest.xml").isFile) File(".") else File("app")
        val namespace = "http://schemas.android.com/apk/res-auto"
        for (name in listOf("standard_v2ray_preferences", "anytls_preferences")) {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val doc = factory.newDocumentBuilder().parse(File(app, "src/main/res/xml/$name.xml"))
            val nodes = doc.getElementsByTagName("moe.matsuri.nb4a.ui.SimpleMenuPreference")
            val picker = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(namespace, "key") == "connectionSecurity" }
            assertEquals("@array/connection_security_entries", picker.getAttributeNS(namespace, "entries"))
            assertEquals("@array/connection_security_values", picker.getAttributeNS(namespace, "entryValues"))
            assertEquals("false", picker.getAttributeNS(namespace, "persistent"))
        }
    }

    @Test fun applicationSharesStandardVlessAndTrojanSecurityWithoutLeakingInactiveKeys() {
        for (kind in listOf("vless", "trojan")) for (mode in modes) {
            val bean = standard(kind)
            ConnectionSecurity.select(bean, mode)
            val entity = ProxyEntity().putBean(bean)
            assertTrue(entity.haveStandardLink())
            val link = entity.toStdLink()
            assertTrue(link.startsWith("$kind://"))
            val copy = standard(kind).apply {
                realityPubKey = ""; realityShortId = ""; protocolOptionsJson = ""
                parseDuckSoft(link.replace("$kind://", "https://").toHttpUrl())
                initializeDefaultValues()
            }
            assertEquals(mode, ConnectionSecurity.mode(copy))
            if (mode == ConnectionSecurity.Mode.REALITY) assertEquals(key, copy.realityPubKey)
            else assertFalse(link.contains("pbk="))
            assertEquals(key, bean.realityPubKey)
        }
        val manual = standard("vless").apply { jsonMode = NodeJson.LEGACY; customOutboundJson = """{"tls":{"server_name":"override.invalid"}}""" }
        assertTrue(ProxyEntity().putBean(manual).toStdLink().startsWith("sn://"))
    }
}
