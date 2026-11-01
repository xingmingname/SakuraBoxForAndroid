package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import org.junit.Assert.*
import org.junit.Test

class SudokuPreferenceLayoutTest {
    private fun paths(section: SudokuPreferenceLayout.Section) = SudokuPreferenceLayout.fields(section).map { it.path }
    private fun bean(raw: String) = SudokuBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
        protocolOptionsJson = raw
    }

    @Test fun everySupportedFieldRemainsReachableExactlyOnce() {
        val visible = SudokuPreferenceLayout.Section.entries.flatMap(::paths)
        assertEquals(52, visible.size)
        assertEquals(SudokuOptions.fields.map { it.path }.toSet(), visible.toSet())
        assertEquals(visible.size, visible.distinct().size)
    }

    @Test fun dailyOptionsAreFirstAndLowFrequencySectionsAreCollapsed() {
        assertEquals(listOf("key", "aead", "ascii", "multiplex", "table_limit"), paths(SudokuPreferenceLayout.Section.BASIC))
        assertEquals(listOf("padding_min", "padding_max", "enable_pure_downlink", "custom_table", "custom_tables"), paths(SudokuPreferenceLayout.Section.ADVANCED))
        assertEquals(listOf("httpmask.disable", "httpmask.mode", "httpmask.tls", "httpmask.host", "httpmask.path_root", "httpmask.multiplex"), paths(SudokuPreferenceLayout.Section.HTTPMASK))
        assertEquals(listOf(SudokuPreferenceLayout.Section.BASIC, SudokuPreferenceLayout.Section.HTTPMASK),
            SudokuPreferenceLayout.Section.entries.filterNot { it.collapsed })
        assertEquals(SudokuPreferenceLayout.Section.LEGACY, SudokuPreferenceLayout.Section.entries.last())
    }

    @Test fun openingAllSectionsDoesNotMaterializeDefaultsOrRewriteImportedData() {
        val raw = """{ "key":"  test key  ","padding_min":0,"enable_pure_downlink":false,"custom_tables":["xpxvvpvv","xpxvvpvv"],"table_limit":0,"http_mask_host":"legacy.invalid","httpmask":{"tls":false,"future":17},"domain_resolver":{"server":"dns-local","rewrite_ttl":0},"future":{"data":[1,2]} }"""
        for (text in listOf("", "{}", raw, """{"httpmask":null,"aead":"none","network_type":["wifi","wifi"]}""")) {
            val source = bean(text)
            val root = SudokuOptions.root(source)
            val before = root.deepCopy()
            for (section in SudokuPreferenceLayout.Section.entries) for (field in SudokuPreferenceLayout.fields(section)) {
                if (SudokuOptions.editable(root, field)) SudokuOptions.text(root, field)
            }
            assertEquals(before, root)
            assertEquals(text, source.protocolOptionsJson)
            assertEquals(text, source.clone().protocolOptionsJson)
            assertEquals(text, KryoConverters.sudokuDeserialize(KryoConverters.serialize(source))!!.protocolOptionsJson)
            assertEquals(text, (parseUniversal(source.toUniversalLink()) as SudokuBean).protocolOptionsJson)
        }
    }

    @Test fun editsAcrossSectionsUseDraftAndKeepUnknownLegacyAndJsonDocuments() {
        val source = bean("""{"key":"original-key","custom_tables":["xpxvvpvv","xpxvvpvv"],"http_mask_tls":true,"httpmask":{"host":"mask.invalid","future":false},"domain_resolver":{"server":"dns-local","future":0},"future":{"keep":true}}""")
        val json = """{"type":"sudoku","server":"json.invalid","server_port":443,"key":"json-key"}"""
        NodeJson.set(source, NodeJson.OUTBOUND, json)
        val before = source.protocolOptionsJson
        val draft = source.clone()
        var root = SudokuOptions.root(draft)
        for ((path, value) in listOf("aead" to "none", "table_limit" to "512", "httpmask.tls" to "false",
            "padding_min" to "0", "domain_resolver.timeout" to "5s", "connect_timeout" to "2s", "http_mask_mode" to "poll")) {
            val field = SudokuPreferenceLayout.Section.entries.flatMap { SudokuPreferenceLayout.fields(it) }.single { it.path == path }
            val changed = SudokuOptions.set(root, field, value)
            val affected = if (path.startsWith("domain_resolver.")) setOf("domain_resolver", path) else setOf(path)
            assertEquals(affected, SudokuPreferenceUpdates.changedFields(root, changed).map { it.path }.toSet())
            root = changed
        }
        draft.protocolOptionsJson = root.toString()
        val saved = draft.clone()
        assertEquals(before, source.protocolOptionsJson) // Discarding a draft leaves the original alone.
        assertEquals(NodeJson.state(source), NodeJson.state(saved))
        assertEquals("none", root["aead"].asString)
        assertEquals(512, root["table_limit"].asInt)
        assertEquals(0, root["padding_min"].asInt)
        val original = SudokuOptions.root(source)
        for (path in listOf("custom_tables", "http_mask_tls", "httpmask.future", "domain_resolver.future", "future"))
            assertEquals(SudokuOptions.raw(original, path), SudokuOptions.raw(SudokuOptions.root(saved), path))
        val imported = SudokuOptions.importOutbound(SudokuOptions.outboundJson(saved).toString())!!
        assertEquals(root, SudokuOptions.root(imported))
    }
}
