package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class ProtocolEditorRoundTripTest {
    private val cacheKey = "protocolUiOptions"

    private class MemoryDao : KeyValuePair.Dao {
        val values = linkedMapOf<String, KeyValuePair>()
        override fun all() = values.values.toList()
        override fun get(key: String) = values[key]
        override fun put(value: KeyValuePair): Long { values[value.key] = value; return 1 }
        override fun delete(key: String): Int = if (values.remove(key) == null) 0 else 1
        override fun reset(): Int = values.size.also { values.clear() }
        override fun insert(list: List<KeyValuePair>) { list.forEach { put(it) } }
    }

    private fun persisted(bean: AbstractBean): AbstractBean = KryoConverters.deserialize(
        bean.javaClass.getDeclaredConstructor().newInstance(), KryoConverters.serialize(bean))

    private fun nodes(): List<Pair<String, AbstractBean>> {
        val result = mutableListOf<Pair<String, AbstractBean>>()
        result += "anytls" to AnyTLSBean().apply { initializeDefaultValues(); password = "synthetic-test"; sni = "example.invalid" }
        for (protocol in listOf("vless", "vmess", "trojan")) for (transport in listOf("tcp", "http", "grpc")) {
            val bean: StandardV2RayBean = if (protocol == "trojan") TrojanBean().apply { initializeDefaultValues(); password = "synthetic-test" }
                else VMessBean().apply { alterId = if (protocol == "vless") -1 else 0; initializeDefaultValues(); uuid = "00000000-0000-0000-0000-000000000001" }
            bean.security = "tls"; bean.sni = "example.invalid"; bean.type = transport; bean.enableMux = true
            result += "$protocol-$transport" to bean
        }
        for (version in listOf(1, 2)) result += "hysteria$version" to HysteriaBean().apply {
            protocolVersion = version; initializeDefaultValues(); authPayload = "synthetic-test"
            authPayloadType = HysteriaBean.TYPE_STRING; obfuscation = "synthetic-obfs"; sni = "example.invalid"
        }
        result += "tuic" to TuicBean().apply { initializeDefaultValues(); protocolVersion = 5; uuid = "00000000-0000-0000-0000-000000000001"; token = "synthetic-test"; sni = "example.invalid" }
        result += "shadowsocks" to ShadowsocksBean().apply { initializeDefaultValues(); method = "aes-128-gcm"; password = "synthetic-test" }
        result += "socks" to SOCKSBean().apply { initializeDefaultValues() }
        result += "http" to HttpBean().apply { initializeDefaultValues(); security = "tls"; sni = "example.invalid" }
        result += "ssh" to SSHBean().apply { initializeDefaultValues(); username = "synthetic"; authType = SSHBean.AUTH_TYPE_NONE }
        result += "wireguard" to WireGuardBean().apply {
            initializeDefaultValues(); localAddress = "10.0.0.2/32"
            privateKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
            peerPublicKey = "CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg="
        }
        result += "shadowtls" to ShadowTLSBean().apply { initializeDefaultValues(); password = "synthetic-test"; sni = "example.invalid" }
        result.forEach { (_, bean) -> bean.serverAddress = "127.0.0.1"; bean.serverPort = 443; bean.name = "synthetic-preserved" }
        return result
    }

    private fun ech(changed: Boolean = false): String {
        val hex = "0041fe0d003d0000200020" + (if (changed) "08" else "07").repeat(32) + "000400010001400e7075626c69632e6578616d706c650000"
        return "-----BEGIN ECH CONFIGS-----\n" + Base64.getEncoder().encodeToString(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) + "\n-----END ECH CONFIGS-----"
    }

    private fun input(field: ProtocolOptions.Field, changed: Boolean = false): String = when (field.path) {
        "tls.ech.config" -> ech(changed)
        "tls.certificate_public_key_sha256" -> Base64.getEncoder().encodeToString(ByteArray(32) { if (changed) 1 else 0 })
        "tls.curve_preferences" -> if (changed) "P256" else "X25519"
        "tls.min_version", "tls.max_version" -> if (changed) "1.3" else "1.2"
        "obfs.type" -> if (changed) "gecko" else "salamander"
        "obfs.min_packet_size" -> if (changed) "700" else "600"
        "obfs.max_packet_size" -> if (changed) "1300" else "1200"
        "initial_packet_size" -> if (changed) "1400" else "1300"
        "stream_receive_window", "connection_receive_window" -> if (changed) "2097152" else "1048576"
        "host_key_algorithms" -> if (changed) "rsa-sha2-512" else "ssh-ed25519"
        "client_version" -> if (changed) "SSH-2.0-ChangedTest" else "SSH-2.0-SakuraBoxTest"
        "cipher" -> if (changed) "aes256-gcm@openssh.com" else "aes128-gcm@openssh.com"
        "mac" -> if (changed) "hmac-sha2-512" else "hmac-sha2-256"
        "kex_algorithm" -> if (changed) "curve25519-sha256@libssh.org" else "curve25519-sha256"
        "transport.method" -> if (changed) "POST" else "GET"
        "tls.ech.query_server_name" -> if (changed) "changed.example.invalid" else "ech.example.invalid"
        else -> when (field.kind) {
            ProtocolOptions.Kind.DURATION -> if (changed) "45s" else "30s"
            ProtocolOptions.Kind.INTEGER -> if (changed) (maxOf(field.min, 1) + 1).toString() else field.min.toString()
            ProtocolOptions.Kind.BOOLEAN -> changed.toString()
            ProtocolOptions.Kind.CHOICE -> if (changed) field.choices.last() else field.choices.first()
            ProtocolOptions.Kind.TEXT -> if (changed) "changed-synthetic" else "synthetic"
            else -> error("Missing explicit sample: ${field.path}")
        }
    }

    private fun output(bean: AbstractBean): String {
        val outbound: SingBoxOptions.SingBoxOption = when (bean) {
            is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
            is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean, false)
            is TuicBean -> buildSingBoxOutboundTuicBean(bean, false)
            is ShadowsocksBean -> buildSingBoxOutboundShadowsocksBean(bean)
            is SOCKSBean -> buildSingBoxOutboundSocksBean(bean)
            is SSHBean -> buildSingBoxOutboundSSHBean(bean)
            is WireGuardBean -> buildSingBoxEndpointWireguardBean(bean)
            // The Android-only default setting lookup is exercised by instrumentation instead.
            is ShadowTLSBean -> SingBoxOptions.Outbound_ShadowTLSOptions().apply {
                type = "shadowtls"; server = bean.serverAddress; server_port = bean.serverPort
                version = bean.version; password = bean.password; tls = buildSingBoxOutboundTLS(bean, false)
            }
            is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean, false)
            else -> error("Missing builder")
        }
        ProtocolOptions.applyTo(outbound, bean)
        outbound._hack_custom_config = bean.customOutboundJson
        outbound._hack_config_map["tag"] = "proxy"
        return Gson().toJson(SingBoxOptions.MyOptions().apply {
            if (bean is WireGuardBean) endpoints = listOf(outbound) else outbounds = listOf(outbound)
            route = SingBoxOptions.RouteOptions().apply { final_ = "proxy" }
        }.asMap())
    }

    @Test fun everyEditableFieldSurvivesSaveReopenPartialEditAndRemoval() {
        val display = mutableListOf<Map<String, String>>()
        val coverage = linkedMapOf<String, MutableList<String>>()
        for ((name, template) in nodes()) {
            val fields = ProtocolOptions.fields(template).filter { ProtocolOptions.editableField(template, it.path) }
            coverage[name] = fields.map { it.path }.toMutableList()
            for (field in fields) {
                val bean = template.clone()
                var baseline = JsonObject()
                if (field.path != "connect_timeout") baseline = ProtocolOptions.set(baseline, ProtocolOptions.Field("connect_timeout", ProtocolOptions.Kind.DURATION), "5s")
                if (field.path.startsWith("obfs.") && field.path != "obfs.type") baseline = ProtocolOptions.set(baseline, ProtocolOptions.Field("obfs.type", ProtocolOptions.Kind.TEXT), "gecko")
                if (field.path.startsWith("multiplex.") && field.path != "multiplex.enabled") baseline = ProtocolOptions.set(baseline, ProtocolOptions.Field("multiplex.enabled", ProtocolOptions.Kind.BOOLEAN), "true")
                if (ProtocolOptions.isNativeQuic(bean) && field.path == "tls.ech.enabled") baseline = ProtocolOptions.set(baseline, ProtocolOptions.Field("tls.ech.config", ProtocolOptions.Kind.LIST), ech())
                bean.protocolOptionsJson = ProtocolOptions.set(baseline, field, input(field)).toString()
                val stored = KryoConverters.serialize(bean)
                val reopened = persisted(bean)
                val dao = MemoryDao()
                val cache = RoomPreferenceDataStore(dao)
                cache.putString(cacheKey, reopened.protocolOptionsJson)
                val root = ProtocolOptions.parse(cache.getString(cacheKey))
                assertEquals("$name ${field.path}", input(field), ProtocolOptions.text(root, field))
                display += mapOf("protocol" to name, "path" to field.path, "kind" to field.kind.name, "value" to ProtocolOptions.text(root, field))
                val unchanged = reopened.clone().apply { protocolOptionsJson = cache.getString(cacheKey) }
                assertArrayEquals("No-op save: $name ${field.path}", stored, KryoConverters.serialize(unchanged))
                assertEquals(output(bean), output(unchanged))
                val edited = ProtocolOptions.set(root, field, input(field, true))
                cache.putString(cacheKey, edited.toString())
                val modified = persisted(reopened.apply { protocolOptionsJson = cache.getString(cacheKey) })
                assertEquals(input(field, true), ProtocolOptions.text(ProtocolOptions.parse(modified.protocolOptionsJson), field))
                assertEquals("synthetic-preserved", modified.name)
                assertEquals(root, ProtocolOptions.set(ProtocolOptions.parse(modified.protocolOptionsJson), field, input(field)))
                val removed = ProtocolOptions.set(ProtocolOptions.parse(modified.protocolOptionsJson), field, "")
                assertNull(ProtocolOptions.get(removed, field.path))
                assertEquals(baseline, removed)
                val cleared = persisted(modified.apply { protocolOptionsJson = if (removed.size() == 0) "" else removed.toString() })
                val expected = template.clone().apply { protocolOptionsJson = baseline.toString() }
                assertEquals(output(expected), output(cleared))
                System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
                    for ((stage, value) in listOf("saved" to unchanged, "changed" to persisted(bean.apply { protocolOptionsJson = edited.toString() }), "removed" to cleared)) {
                        File(directory, "editor-$name-${field.path}-$stage.json").writeText(output(value))
                    }
                }
            }
        }
        assertEquals(13, nodes().map { ProtocolOptions.protocol(it.second) }.toSet().size)
        System.getenv("NEKO_CONFIG_FIXTURES")?.let {
            File(it, "advanced-editor-matrix").mkdirs()
            File(it, "advanced-editor-matrix/display.json").writeText(Gson().toJson(display))
            File(it, "advanced-editor-matrix/coverage.json").writeText(Gson().toJson(coverage))
        }
    }

    @Test fun historicalEmptyNullFalseZeroAndUnknownValuesAreNotNormalizedOnNoOpSave() {
        val raw = """{ "idle_session_timeout":"0", "min_idle_session":0, "tls":{"disable_sni":false}, "client_metadata":"", "connect_timeout":null, "future":{"kept":[0,false,""]} }"""
        val bean = nodes().first().second.apply { protocolOptionsJson = raw }
        val reopened = persisted(bean)
        val cache = RoomPreferenceDataStore(MemoryDao())
        cache.putString(cacheKey, reopened.protocolOptionsJson)
        val fields = ProtocolOptions.fields(reopened).associateBy { it.path }
        val root = ProtocolOptions.parse(cache.getString(cacheKey))
        assertEquals("0", ProtocolOptions.text(root, fields.getValue("idle_session_timeout")))
        assertEquals("0", ProtocolOptions.text(root, fields.getValue("min_idle_session")))
        assertEquals("false", ProtocolOptions.text(root, fields.getValue("tls.disable_sni")))
        assertEquals("", ProtocolOptions.text(root, fields.getValue("client_metadata")))
        assertEquals("", ProtocolOptions.text(root, fields.getValue("connect_timeout")))
        reopened.protocolOptionsJson = cache.getString(cacheKey)
        assertEquals(raw, persisted(reopened).protocolOptionsJson)
        val changed = ProtocolOptions.set(root, fields.getValue("idle_session_timeout"), "45s")
        assertEquals(root.get("future"), changed.get("future"))
        assertTrue(changed.get("connect_timeout").isJsonNull)
        assertEquals("", changed.get("client_metadata").asString)
    }

    @Test fun newAndLegacyNodesHaveNoForcedAdvancedDefaults() {
        for ((_, bean) in nodes()) {
            assertEquals("", persisted(bean).protocolOptionsJson)
            val cache = RoomPreferenceDataStore(MemoryDao())
            cache.putString(cacheKey, bean.protocolOptionsJson)
            for (field in ProtocolOptions.fields(bean)) assertEquals("", ProtocolOptions.text(ProtocolOptions.parse(cache.getString(cacheKey)), field))
            bean.protocolOptionsJson = cache.getString(cacheKey)
            assertEquals("", persisted(bean).protocolOptionsJson)
        }
    }

    @Test fun clearAllUsesTheBaseNodeAndPreservesExplicitManualOverrides() {
        for ((_, bean) in nodes()) {
            val original = output(bean)
            bean.protocolOptionsJson = """{"connect_timeout":"30s"}"""
            val reopened = persisted(bean)
            val cache = RoomPreferenceDataStore(MemoryDao())
            cache.putString(cacheKey, reopened.protocolOptionsJson)
            assertNotEquals(original, output(reopened))
            cache.putString(cacheKey, "")
            reopened.protocolOptionsJson = cache.getString(cacheKey)
            assertEquals(original, output(persisted(reopened)))
            reopened.customOutboundJson = """{"connect_timeout":"45s"}"""
            val withManualOverride = output(reopened)
            reopened.protocolOptionsJson = """{"connect_timeout":"30s"}"""
            assertEquals(withManualOverride, output(reopened))
            reopened.protocolOptionsJson = ""
            assertEquals(withManualOverride, output(persisted(reopened)))
        }
    }
}
