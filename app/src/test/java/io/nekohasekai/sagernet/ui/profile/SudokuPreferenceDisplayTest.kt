package io.nekohasekai.sagernet.ui.profile

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.fmt.sudoku.SudokuShareLink
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SudokuPreferenceDisplayTest {
    private val emptyChoice = "__sudoku_preserved_empty__"
    private fun field(path: String) = SudokuOptions.fields.single { it.path == path }
    private fun bean(raw: String) = SudokuBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
        protocolOptionsJson = raw
    }
    private fun choice(root: JsonObject, path: String): CharSequence {
        val value = SudokuOptions.text(root, field(path))
        return SudokuPreferenceDisplay.choice(value, value, emptyChoice)
    }

    @Test fun blankTextDoesNotCreateAPlaceholderOrHideLiteralNone() {
        assertEquals("", SudokuPreferenceDisplay.text(null))
        assertEquals("", SudokuPreferenceDisplay.text(""))
        for (value in listOf("none", "off", "0", "false", " ", "数独")) {
            assertEquals(value, SudokuPreferenceDisplay.text(value))
        }
    }

    @Test fun choiceSeparatesMissingOrEmptyFromRealNoneAndUnknownValues() {
        assertEquals("", SudokuPreferenceDisplay.choice(null, "未设置", emptyChoice))
        assertEquals("", SudokuPreferenceDisplay.choice("", "未设置", emptyChoice))
        assertEquals("", SudokuPreferenceDisplay.choice(emptyChoice, "空值（已显式设置）", emptyChoice))
        assertEquals("none", SudokuPreferenceDisplay.choice("none", "none", emptyChoice))
        assertEquals("none", SudokuPreferenceDisplay.choice("none", null, emptyChoice))
        assertEquals("33", SudokuPreferenceDisplay.choice("33", null, emptyChoice))
        assertEquals("无限制", SudokuPreferenceDisplay.choice("0", "无限制", emptyChoice))
    }

    @Test fun emptySecretsAreBlankAndNonemptyKeysRemainMaskedWithoutTrimming() {
        assertEquals("", SudokuPreferenceDisplay.secret(null))
        assertEquals("", SudokuPreferenceDisplay.secret(""))
        assertEquals("••••", SudokuPreferenceDisplay.secret("none"))
        assertEquals("••", SudokuPreferenceDisplay.secret("  "))
    }

    @Test fun longSummariesAndKeysStayBoundedWithoutChangingEditorValues() {
        val tables = (1..4000).joinToString("\n") { "xpxvvpvv" }
        assertEquals("xpxvvpvv\nxpxvvpvv\nxpxvvpvv…", SudokuPreferenceDisplay.text(tables))
        val long = "a".repeat(159) + "\uD83C\uDF38" + "b".repeat(1000)
        assertEquals("a".repeat(159) + "…", SudokuPreferenceDisplay.text(long))
        assertEquals("a".repeat(160), SudokuPreferenceDisplay.text("a".repeat(160)))
        assertEquals(16, SudokuPreferenceDisplay.secret("key".repeat(10000)).length)
        assertEquals("x".repeat(160) + "…", SudokuPreferenceDisplay.choice("x".repeat(1000), null, emptyChoice))
        val node = bean("{}")
        node.protocolOptionsJson = SudokuOptions.set(JsonObject(), field("custom_tables"), tables).toString()
        assertEquals(tables, SudokuOptions.text(SudokuOptions.root(node.clone()), field("custom_tables")))
        assertEquals(4000, SudokuOptions.root(node)["custom_tables"].asJsonArray.size())
    }

    @Test fun sixNumericLimitsKeepTheirValueAndSummaryAfterSaveReopenAndCopy() {
        val limit = field("table_limit")
        assertEquals(listOf("32", "64", "128", "256", "512", "0"), limit.choices)
        assertEquals("32", choice(JsonObject(), "table_limit"))
        for (value in limit.choices) {
            val node = bean("""{"key":"ui-display-test-key","aead":"none","padding_min":0,"enable_pure_downlink":false}""")
            node.protocolOptionsJson = SudokuOptions.set(SudokuOptions.root(node), limit, value).toString()
            val entity = ProxyEntity().putBean(node.clone())
            val reopened = KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity)).requireBean() as SudokuBean
            val root = SudokuOptions.root(reopened)
            val actual = SudokuOptions.text(root, limit)
            val label = if (actual == "0") "无限制" else actual
            assertEquals(value, actual)
            assertEquals(label, SudokuPreferenceDisplay.choice(actual, label, emptyChoice))
            assertEquals("none", choice(root, "aead"))
            assertEquals(0, root["padding_min"].asInt)
            assertFalse(root["enable_pure_downlink"].asBoolean)
            if (value == "32") assertFalse(root.has("table_limit")) else assertEquals(value.toInt(), root["table_limit"].asInt)
            val imported = SudokuOptions.importOutbound(SudokuOptions.outboundJson(reopened).toString())!!
            assertEquals(reopened.protocolOptionsJson, imported.protocolOptionsJson)
        }
    }

    @Test fun officialUriDefaultsAndExplicitNoneStayNoneThroughImportShareAndReopen() {
        for (extra in listOf("", ",\"e\":\"none\"")) {
            val payload = """{"h":"example.invalid","p":443,"k":"ui-display-test-key"$extra}"""
            val uri = "sudoku://" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            val imported = SudokuShareLink.parse(uri)
            for (node in listOf(imported, imported.clone(), parseUniversal(imported.toUniversalLink()) as SudokuBean,
                SudokuShareLink.parse(SudokuShareLink.export(imported)!!))) {
                val root = SudokuOptions.root(node)
                assertEquals("none", root["aead"].asString)
                assertEquals("none", choice(root, "aead"))
            }
        }
    }

    @Test fun identicalBlankSummariesNeverNormalizeMissingEmptyNullOrAdvancedData() {
        for (raw in listOf("{}", """{"aead":""}""", """{"aead":"none"}""",
            """{"aead":null,"custom_tables":[],"httpmask":{"host":"","tls":false},"future":{"keep":true}}""")) {
            val node = bean(raw)
            val root = SudokuOptions.root(node)
            val before = root.deepCopy()
            if (SudokuOptions.editable(root, field("aead"))) {
                val expected = if (root.get("aead")?.asString == "none") "none" else ""
                assertEquals(expected, choice(root, "aead"))
            } else assertTrue(root["aead"].isJsonNull)
            assertEquals(before, root)
            assertEquals(raw, node.clone().protocolOptionsJson)
            assertEquals(raw, (parseUniversal(node.toUniversalLink()) as SudokuBean).protocolOptionsJson)
        }
    }

    @Test fun displayDoesNotChangeIndependentJsonModeOrOriginalOptions() {
        val node = bean("""{"key":"ui-display-test-key","aead":"none","table_limit":0}""")
        val json = """{"type":"sudoku","server":"127.0.0.1","server_port":443,"key":"json-key","table_limit":64}"""
        NodeJson.set(node, NodeJson.OUTBOUND, json)
        val state = NodeJson.state(node)
        val raw = node.protocolOptionsJson
        assertEquals("none", choice(SudokuOptions.root(node), "aead"))
        assertEquals("无限制", SudokuPreferenceDisplay.choice("0", "无限制", emptyChoice))
        assertEquals(raw, node.protocolOptionsJson)
        assertEquals(state, NodeJson.state(node.clone()))
        assertEquals(json, (parseUniversal(node.toUniversalLink()) as SudokuBean).customOutboundJson)
    }
}
