package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SudokuOptionsTest {
    private fun bean() = SudokuBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443 }
    private fun field(path: String) = SudokuOptions.fields.single { it.path == path }
    private fun configured(text: String) = bean().apply { protocolOptionsJson = text }
    private fun generated(bean: SudokuBean): JsonObject {
        val outbound = SudokuOptions.buildOutbound(bean)
        ProtocolOptions.applyTo(outbound, bean)
        outbound._hack_custom_config = NodeJson.legacyOutbound(bean)
        return Gson().toJsonTree(outbound.asMap()).asJsonObject
    }
    private fun fixture(name: String, bean: SudokuBean) {
        val outbound = generated(bean).apply { addProperty("tag", "proxy") }
        val root = JsonObject().apply {
            add("outbounds", com.google.gson.JsonArray().apply { add(outbound) })
            add("route", JsonObject().apply { addProperty("final", "proxy") })
        }
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory -> File(directory).mkdirs(); File(directory, "sudoku-ui-$name.json").writeText(root.toString()) }
    }

    @Test fun newNodeDoesNotMaterializeCoreDefaults() {
        val current = bean()
        val json = generated(current)
        assertEquals(setOf("type", "server", "server_port"), json.keySet())
        assertEquals("sudoku", json["type"].asString)
        assertEquals("", current.protocolOptionsJson)
        for (field in SudokuOptions.fields) assertNull(SudokuOptions.raw(SudokuOptions.root(current), field.path))
    }

    @Test fun allSupportedControlsSaveOnlyTheirOwnValueAndPreserveUnknownFields() {
        for (field in SudokuOptions.fields) {
            val initial = NodeJson.parse("""{"future_option":{"keep":true}}""")
            val input = when (field.kind) {
                SudokuOptions.Kind.BOOLEAN -> "false"
                SudokuOptions.Kind.INTEGER -> "0"
                SudokuOptions.Kind.MARK -> "0x123"
                SudokuOptions.Kind.CHOICE -> field.choices.first()
                SudokuOptions.Kind.TABLE_LIMIT -> "64"
                SudokuOptions.Kind.LIST -> if (field.choices.isEmpty()) "xpxvvpvv\nxpvvxvpv" else field.choices.first()
                else -> "synthetic-test"
            }
            val updated = SudokuOptions.set(initial, field, input)
            assertEquals(initial["future_option"], updated["future_option"])
            val restored = KryoConverters.sudokuDeserialize(KryoConverters.serialize(configured(updated.toString())))!!
            assertEquals(updated, SudokuOptions.root(restored))
            assertEquals(input, SudokuOptions.text(updated, field))
            val coreFields = generated(restored).apply { listOf("type", "server", "server_port").forEach { remove(it) } }
            assertEquals("Production JSON mapping for ${field.path}", updated, coreFields)
        }
    }

    @Test fun omissionZeroFalseEmptyAndNullRemainDistinctAcrossReopenAndImport() {
        for (options in listOf("{}", """{"padding_min":0,"padding_max":0,"enable_pure_downlink":false}""",
            """{"custom_table":"","custom_tables":[],"httpmask":{"disable":false,"tls":false,"host":""}}""",
            """{"padding_min":null,"enable_pure_downlink":null,"httpmask":null}""")) {
            val current = configured(options)
            val copy = current.clone()
            assertEquals(NodeJson.parse(options), SudokuOptions.root(copy))
            val imported = SudokuOptions.importOutbound(SudokuOptions.outboundJson(current).toString())!!
            assertEquals(SudokuOptions.root(current), SudokuOptions.root(imported))
            assertEquals(SudokuOptions.root(current), SudokuOptions.root(imported.clone()))
        }
        val explicit = configured("""{"key":"test-only","padding_min":0,"padding_max":0,"enable_pure_downlink":false,"httpmask":{"disable":false,"tls":false}}""")
        val json = generated(explicit)
        assertEquals(0, json["padding_min"].asInt)
        assertFalse(json["enable_pure_downlink"].asBoolean)
        assertFalse(json.getAsJsonObject("httpmask")["tls"].asBoolean)
        fixture("zero-false", explicit)
    }

    @Test fun sharedAndSplitKeysKeepExactBytesIncludingAfterCopy() {
        // Canonical synthetic scalars: master 3 and split (1, 2), for format testing only.
        val masterKey = "03" + "00".repeat(31)
        val splitKey = "01" + "00".repeat(31) + "02" + "00".repeat(31)
        for (key in listOf("shared test key", masterKey, splitKey, "  shared-with-whitespace  ")) {
            val current = bean()
            current.protocolOptionsJson = SudokuOptions.set(JsonObject(), field("key"), key).toString()
            val entity = ProxyEntity(groupId = 42, userOrder = 7).putBean(current)
            assertEquals(ProxyEntity.TYPE_SUDOKU, entity.type)
            assertEquals("Sudoku", entity.displayType())
            assertFalse(entity.needExternal()); assertFalse(entity.haveStandardLink())
            val restored = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity))
            assertEquals(42L, restored.groupId); assertEquals(7L, restored.userOrder)
            assertEquals(key, generated(restored.requireBean() as SudokuBean)["key"].asString)
            val linked = parseUniversal(current.toUniversalLink()) as SudokuBean
            assertEquals(current.protocolOptionsJson, linked.protocolOptionsJson)
            if (key == masterKey) fixture("master-key", current)
            if (key == splitKey) fixture("split-key", current)
        }
    }

    @Test fun httpmaskModesLegacyFieldsAndNestedPrecedenceAreNotRewrittenByUi() {
        for (mode in listOf("legacy", "stream", "poll", "auto", "ws")) {
            val current = configured("""{"key":"test-only","aead":"aes-128-gcm","ascii":"up_ascii_down_entropy","http_mask_mode":"ws","http_mask_tls":true,"httpmask":{"mode":"$mode","tls":false,"host":"example.invalid","path_root":"abc","multiplex":"auto"}}""")
            assertEquals(SudokuOptions.root(current), SudokuOptions.root(SudokuOptions.importOutbound(SudokuOptions.outboundJson(current).toString())!!))
            assertEquals(mode, generated(current).getAsJsonObject("httpmask")["mode"].asString)
            assertTrue(generated(current)["http_mask_tls"].asBoolean)
            assertFalse(generated(current).getAsJsonObject("httpmask")["tls"].asBoolean)
            fixture("httpmask-$mode", current)
        }
    }

    @Test fun encodingAlgorithmsPaddingAndTablesReachProductionSerializer() {
        for (aead in listOf("chacha20-poly1305", "aes-128-gcm", "none")) {
            for (ascii in SudokuOptions.asciiModes) {
                val current = configured("""{"key":"test-only","aead":"$aead","ascii":"$ascii","custom_table":"xpxvvpvv","custom_tables":["xpxvvpvv","xpvvxvpv"],"padding_min":0,"padding_max":100,"multiplex":"off"}""")
                val json = generated(current)
                assertEquals(aead, json["aead"].asString); assertEquals(ascii, json["ascii"].asString)
                assertEquals(0, json["padding_min"].asInt); assertEquals(100, json["padding_max"].asInt)
                assertEquals(2, json["custom_tables"].asJsonArray.size())
                fixture("$aead-$ascii", current)
            }
        }
    }

    @Test fun resolverUnionAndGenericDialFieldsKeepTheirTypesAndUneditedData() {
        val initial = NodeJson.parse("""{"domain_resolver":"dns-direct","routing_mark":"0x123","tcp_fast_open":false,"network_type":"wifi","unknown":{"data":[1,2]}}""")
        val changed = SudokuOptions.set(initial, field("domain_resolver.timeout"), "5s")
        assertEquals("dns-direct", changed.getAsJsonObject("domain_resolver")["server"].asString)
        assertEquals("5s", changed.getAsJsonObject("domain_resolver")["timeout"].asString)
        assertEquals(initial["routing_mark"], changed["routing_mark"])
        assertEquals(initial["network_type"], changed["network_type"])
        assertEquals(initial["unknown"], changed["unknown"])
        val cleared = SudokuOptions.set(changed, field("tcp_fast_open"), null)
        assertFalse(cleared.has("tcp_fast_open")); assertTrue(changed.has("tcp_fast_open"))
    }

    @Test fun nonRepresentableJsonIsRetainedInsteadOfCoercedOrDeleted() {
        val initial = NodeJson.parse("""{"key":123,"httpmask":"future","custom_tables":["one\ntwo"],"future":{"enabled":false}}""")
        assertEquals(listOf("future"), SudokuOptions.retainedFields(initial))
        for (name in listOf("key", "httpmask.mode", "custom_tables")) assertFalse(SudokuOptions.editable(initial, field(name)))
        assertFalse(SudokuOptions.editable(NodeJson.parse("""{"custom_tables":[""]}"""), field("custom_tables")))
        assertFalse(SudokuOptions.editable(NodeJson.parse("""{"network_type":["wifi","wifi"]}"""), field("network_type")))
        assertFalse(SudokuOptions.editable(NodeJson.parse("""{"network_type":""}"""), field("network_type")))
        val current = configured(initial.toString())
        assertEquals(initial, SudokuOptions.root(current.clone()))
        val invalidBase = """{"type":"sudoku","server":null,"server_port":"future","key":"test"}"""
        val imported = SudokuOptions.importOutbound(invalidBase)!!
        assertEquals(NodeJson.OUTBOUND, imported.jsonMode)
        assertEquals(invalidBase, imported.customOutboundJson)
    }

    @Test fun networkMultiSelectionPreservesUnchangedOrderScalarAndOmission() {
        val option = field("network_type")
        for (text in listOf("{}", """{"network_type":[]}""")) {
            val root = NodeJson.parse(text)
            assertEquals(root, SudokuOptions.setSelection(root, option, emptySet()))
        }
        val scalar = NodeJson.parse("""{"network_type":"wifi"}""")
        assertEquals(scalar, SudokuOptions.setSelection(scalar, option, setOf("wifi")))
        val array = NodeJson.parse("""{"network_type":["cellular","wifi"],"udp_fragment":false}""")
        assertEquals(array, SudokuOptions.setSelection(array, option, setOf("wifi", "cellular")))
        val changed = SudokuOptions.setSelection(array, option, setOf("ethernet", "wifi"))
        assertEquals(NodeJson.parse("""{"network_type":["wifi","ethernet"],"udp_fragment":false}"""), changed)
    }

    @Test fun ordinaryUiEditsPreserveIndependentJsonModeAndBothDocuments() {
        val original = configured("""{"key":"ordinary-key","padding_min":0}""")
        NodeJson.set(original, NodeJson.OUTBOUND, """{"type":"sudoku","server":"json.invalid","server_port":8443,"key":"json-key","enable_pure_downlink":false}""")
        NodeJson.set(original, NodeJson.FULL, """{"outbounds":[{"type":"sudoku","tag":"proxy","server":"full.invalid","server_port":443,"key":"full-key"}],"route":{"final":"proxy"}}""")
        for (mode in listOf(NodeJson.NONE, NodeJson.OUTBOUND, NodeJson.FULL)) {
            original.jsonMode = mode
            val edited = original.clone().apply { serverAddress = "ordinary-edited.invalid"; protocolOptionsJson = """{"key":"new-ordinary-key"}""" }
            val state = NodeJson.state(original)
            assertEquals(state, NodeJson.state(edited.clone()))
            assertEquals("new-ordinary-key", generated(edited)["key"].asString)
            assertEquals("json-key", NodeJson.parse(edited.customOutboundJson)["key"].asString)
        }
    }

    @Test fun storingCoreInvalidValuesDoesNotIntroduceASaveGate() {
        var root = JsonObject()
        root = SudokuOptions.set(root, field("padding_min"), "-1")
        root = SudokuOptions.set(root, field("padding_max"), "999")
        root = SudokuOptions.set(root, field("connect_timeout"), "not-a-duration")
        val restored = configured(root.toString()).clone()
        assertEquals(-1, SudokuOptions.root(restored)["padding_min"].asInt)
        assertEquals(999, SudokuOptions.root(restored)["padding_max"].asInt)
    }

    @Test fun groupAddressResolutionKeepsHttpmaskHostnameAndExplicitOverrides() {
        val nested = configured("""{"key":"test-only","httpmask":{"tls":true}}""").apply { serverAddress = "original.invalid" }
        SudokuOptions.retainHostBeforeAddressRewrite(nested)
        assertEquals("original.invalid", SudokuOptions.root(nested).getAsJsonObject("httpmask")["host"].asString)
        val emptyNested = configured("""{"httpmask":{},"http_mask_host":"ignored.invalid"}""").apply { serverAddress = "nested.invalid" }
        SudokuOptions.retainHostBeforeAddressRewrite(emptyNested)
        assertEquals("nested.invalid", SudokuOptions.root(emptyNested).getAsJsonObject("httpmask")["host"].asString)
        assertEquals("ignored.invalid", SudokuOptions.root(emptyNested)["http_mask_host"].asString)
        val explicit = configured("""{"key":"test-only","http_mask_host":"explicit.invalid"}""")
        SudokuOptions.retainHostBeforeAddressRewrite(explicit)
        assertEquals("explicit.invalid", SudokuOptions.root(explicit)["http_mask_host"].asString)
        val disabled = configured("""{"key":"test-only","disable_http_mask":true}""")
        val before = disabled.protocolOptionsJson
        SudokuOptions.retainHostBeforeAddressRewrite(disabled)
        assertEquals(before, disabled.protocolOptionsJson)
    }
}
