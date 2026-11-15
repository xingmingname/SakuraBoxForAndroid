package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.nakis.NakisBean
import io.nekohasekai.sagernet.fmt.nakis.NakisOptions
import org.junit.Assert.*
import org.junit.Test

class NakisOptionsTest {
    private val uuid = "6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11"
    private fun outbound(bean: NakisBean): JsonObject {
        val dto = NakisOptions.buildOutbound(bean)
        ProtocolOptions.applyTo(dto, bean)
        return Gson().toJsonTree(dto.asMap()).asJsonObject
    }

    @Test fun legacyOptionsSurviveKryoAndInternalShareWithoutLeakingIntoNativeOutbound() {
        val bean = NakisBean().apply {
            initializeDefaultValues()
            serverAddress = "::1"
            serverPort = 8443
            name = "Nakis 节点"
            protocolOptionsJson = """{
                "uuid":"$uuid","tls":false,"pool_max_uses":0,"send_quiet":0,"future_option":{"keep":true},
                "performance_mode":"fast","preconnect":true,"detour":"stale","listen":"127.0.0.1:1080",
                "log_level":"debug","type":"socks","tag":"stale","server":"wrong.invalid","server_port":9
            }"""
        }
        val entity = ProxyEntity(groupId = 7, userOrder = 3).putBean(bean)
        assertEquals(ProxyEntity.TYPE_NAKIS, entity.type)
        assertFalse(entity.needExternal())
        assertFalse(entity.haveStandardLink())
        val copy = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity))
        val restored = copy.requireBean() as NakisBean
        assertEquals(7L, copy.groupId)
        assertEquals(bean.name, restored.name)
        assertEquals(bean.protocolOptionsJson, restored.protocolOptionsJson)
        assertEquals(bean.protocolOptionsJson, (parseUniversal(entity.toStdLink()) as NakisBean).protocolOptionsJson)
        val generated = outbound(restored)
        assertEquals(setOf("type", "server", "server_port", "uuid", "tls", "pool_max_uses", "send_quiet"), generated.keySet())
        assertEquals("nakis", generated["type"].asString)
        assertEquals("::1", generated["server"].asString)
        assertEquals(8443, generated["server_port"].asInt)
        assertEquals(uuid, generated["uuid"].asString)
        assertFalse(generated["tls"].asBoolean)
        assertEquals(0, generated["pool_max_uses"].asInt)
        assertEquals(0, generated["send_quiet"].asInt)
        assertTrue(ProtocolOptions.parse(restored.protocolOptionsJson).getAsJsonObject("future_option")["keep"].asBoolean)
    }

    @Test fun unsupportedRawOutboundFieldsStayInJsonModeOnly() {
        val raw = """{"type":"nakis","tag":"N","server":"host.invalid","server_port":8443,"uuid":"$uuid",
            "tls":false,"detour":"upstream","bind_interface":"wlan0","future_option":{"keep":true}}"""
        val restored = NakisOptions.importOutbound(raw)!!.clone()
        assertTrue(NodeJson.enabled(restored))
        assertEquals("host.invalid", restored.serverAddress)
        assertEquals(8443, restored.serverPort)
        assertEquals(raw, NodeJson.saved(restored, NodeJson.OUTBOUND))
        val json = NodeJson.outbound(NodeJson.saved(restored, NodeJson.OUTBOUND), "proxy")
        assertEquals("upstream", json["detour"].asString)
        assertEquals("wlan0", json["bind_interface"].asString)
        assertTrue(json.getAsJsonObject("future_option")["keep"].asBoolean)

        restored.jsonMode = NodeJson.NONE
        assertEquals(setOf("uuid", "tls"), ProtocolOptions.parse(restored.protocolOptionsJson).keySet())
        assertFalse(outbound(restored).has("detour"))
        assertFalse(outbound(restored)["tls"].asBoolean)
        assertEquals(raw, NodeJson.saved(restored, NodeJson.OUTBOUND))
    }

    @Test fun settingsPreserveTypesAndDoNotNormalizeSecrets() {
        assertThrows(IllegalArgumentException::class.java) {
            NakisOptions.parseValue("performance_mode", ProtocolOptions.Kind.TEXT, "fast")
        }
        assertFalse(NakisOptions.parseValue("tls", ProtocolOptions.Kind.BOOLEAN, "false")!!.asBoolean)
        assertNull(NakisOptions.parseValue("tls", ProtocolOptions.Kind.BOOLEAN, ""))
        assertEquals(0, NakisOptions.parseValue("pool_max_uses", ProtocolOptions.Kind.INTEGER, "0")!!.asInt)
        assertEquals(" $uuid ", NakisOptions.parseValue("uuid", ProtocolOptions.Kind.TEXT, " $uuid ")!!.asString)
        assertEquals("[[10,20],[30,40]]", NakisOptions.parseValue("shape_up", ProtocolOptions.Kind.TEXT, "[[10,20],[30,40]]").toString())
        assertEquals("[]", NakisOptions.parseValue("shape_up", ProtocolOptions.Kind.TEXT, "[]").toString())
        assertEquals("[\"aa:bb\",\"CC:DD\"]", NakisOptions.parseValue("tls_pins", ProtocolOptions.Kind.LIST, "aa:bb\nCC:DD").toString())
    }

    @Test fun editorDefaultsStayOmittedAndOfficialDurationsKeepTheirExplicitValues() {
        val empty = JsonObject()
        val kind = ProtocolOptions.Kind.DURATION
        assertEquals("45s", NakisOptions.editorDefaults["pool_max_age"])
        assertEquals("20ms", NakisOptions.editorDefaults["shape_wait"])
        assertEquals("", NakisOptions.editorText(empty, "pool_max_age", kind))
        assertNull(NakisOptions.parseValue("pool_max_age", kind, ""))
        for (value in listOf("0", "-5s", "+1h30m", ".5ms", "1us", "1µs")) {
            assertEquals(value, NakisOptions.parseValue("pool_max_age", kind, value)?.asString)
        }
        assertThrows(IllegalArgumentException::class.java) { NakisOptions.parseValue("pool_max_age", kind, "1e5s") }
    }

    @Test fun jsonTakeoverStaysSeparateAndVpnChecksNakisDialer() {
        val raw = """{"type":"nakis","tag":"N","server":"host.invalid","server_port":8443,"uuid":"$uuid","tls":false}"""
        val bean = NakisOptions.importOutbound(raw)!!
        val options = bean.protocolOptionsJson
        NodeJson.set(bean, NodeJson.OUTBOUND, raw)
        assertTrue(NodeJson.enabled(bean.clone()))
        assertEquals(options, bean.clone().protocolOptionsJson)
        val full = NodeJson.parse("""{
            "inbounds":[{"type":"tun","address":["172.19.0.1/30"],"auto_route":true}],
            "outbounds":[$raw,{"type":"socks","tag":"other","server":"127.0.0.1","server_port":1080,"bind_interface":"wlan0"}],
            "route":{"final":"N","auto_detect_interface":false}
        }""")
        NodeJson.validateReferences(full)
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireVpn(full, true, setOf("wlan0")) }
        full.getAsJsonObject("route").addProperty("auto_detect_interface", true)
        NodeJson.requireVpn(full, true, setOf("wlan0"))
    }
    @Test fun everyNativeEditorFieldReachesOutboundAndSurvivesKryo() {
        val entries = linkedMapOf(
            "uuid" to (ProtocolOptions.Kind.TEXT to uuid),
            "tls" to (ProtocolOptions.Kind.BOOLEAN to "true"),
            "sni" to (ProtocolOptions.Kind.TEXT to "test.invalid"),
            "tls_pins" to (ProtocolOptions.Kind.LIST to "ab".repeat(32)),
            "ech_config" to (ProtocolOptions.Kind.TEXT to "AAQBAgME"),
            "server_alternates" to (ProtocolOptions.Kind.LIST to "[::1]:8444,other.invalid:8445"),
            "pool_lo" to (ProtocolOptions.Kind.INTEGER to "3"),
            "pool_hi" to (ProtocolOptions.Kind.INTEGER to "12"),
            "pool_max_uses" to (ProtocolOptions.Kind.INTEGER to "0"),
            "pool_max_age" to (ProtocolOptions.Kind.DURATION to "30s"),
            "race_count" to (ProtocolOptions.Kind.INTEGER to "4"),
            "dial_timeout" to (ProtocolOptions.Kind.DURATION to "3s"),
            "handshake_timeout" to (ProtocolOptions.Kind.DURATION to "6s"),
            "keepalive" to (ProtocolOptions.Kind.DURATION to "7s"),
            "congestion" to (ProtocolOptions.Kind.TEXT to "reno"),
            "send_pack" to (ProtocolOptions.Kind.INTEGER to "4096"),
            "send_tail" to (ProtocolOptions.Kind.INTEGER to "0"),
            "send_quiet" to (ProtocolOptions.Kind.INTEGER to "0"),
            "shape_up" to (ProtocolOptions.Kind.TEXT to "[[128,256]]"),
            "shape_wait" to (ProtocolOptions.Kind.DURATION to "12ms"),
            "half_close_timeout" to (ProtocolOptions.Kind.DURATION to "15s"),
            "udp_jitter" to (ProtocolOptions.Kind.DURATION to "0s"),
        )
        val options = JsonObject()
        for ((field, input) in entries) {
            options.add(field, NakisOptions.parseValue(field, input.first, input.second))
        }
        val bean = NakisBean().apply {
            initializeDefaultValues()
            name = "Display name"
            serverAddress = "test.invalid"
            serverPort = 8443
            protocolOptionsJson = options.toString()
        }.clone()
        assertEquals("Display name", bean.name)
        val generated = outbound(bean)
        assertEquals("test.invalid", generated["server"].asString)
        assertEquals(8443, generated["server_port"].asInt)
        for ((field, input) in entries) {
            assertTrue(field, NakisOptions.isEditable(options, field))
            assertEquals(field, options[field], generated[field])
            assertEquals(
                field,
                options[field],
                NakisOptions.parseValue(field, input.first, NakisOptions.editorText(options, field, input.first)),
            )
        }
    }

    @Test fun inactiveFieldsStayStoredAndReturnWhenTheirModeIsRestored() {
        val raw = """{"type":"nakis","server":"test.invalid","server_port":8443,"uuid":"$uuid",
            "tls":false,"sni":"test.invalid","tls_pins":["synthetic"],"ech_config":"synthetic",
            "shape_up":[],"shape_wait":"12ms"}"""
        val bean = NakisOptions.importOutbound(raw)!!.clone()
        val stored = ProtocolOptions.parse(bean.protocolOptionsJson)
        val conditional = listOf("sni", "tls_pins", "ech_config", "shape_wait")
        for (field in conditional) {
            assertTrue(stored.has(field))
            assertFalse(NakisOptions.isEditable(stored, field))
            assertFalse(outbound(bean).has(field))
        }
        NodeJson.set(bean, NodeJson.OUTBOUND, raw)
        assertEquals(raw, NodeJson.saved(bean, NodeJson.OUTBOUND))
        bean.jsonMode = NodeJson.NONE
        stored.remove("tls")
        stored.add("shape_up", NakisOptions.parseValue("shape_up", ProtocolOptions.Kind.TEXT, "[[128,256]]"))
        bean.protocolOptionsJson = stored.toString()
        val generated = outbound(bean.clone())
        assertFalse(generated.has("tls"))
        for (field in conditional) {
            assertTrue(NakisOptions.isEditable(stored, field))
            assertEquals(stored[field], generated[field])
        }
        assertEquals(raw, NodeJson.saved(bean, NodeJson.OUTBOUND))
    }
}
