package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.fmt.sudoku.SudokuShareLink
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SudokuTableLimitTest {
    private val field = SudokuOptions.fields.single { it.path == "table_limit" }
    private fun bean() = SudokuBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443; name = "数独 limit"
        protocolOptionsJson = """{"key":"table-limit-synthetic-key","ascii":"prefer_entropy","aead":"none","padding_min":5,"padding_max":15,"custom_tables":["xpxvvpvv"]}"""
    }
    private fun fixture(name: String, bean: SudokuBean, json: String? = null) {
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { base ->
            val folder = File(base,"table-limits").apply { mkdirs() }
            File(folder,"$name.json").writeText(json ?: SudokuOptions.outboundJson(bean).toString())
        }
    }

    @Test fun sixChoicesAreNumericIndependentAndSurviveReopenCloneKryoAndJson() {
        assertEquals(listOf("32","64","128","256","512","0"), field.choices)
        assertEquals("32", SudokuOptions.text(JsonObject(),field))
        val untouched = bean()
        val original = untouched.protocolOptionsJson
        for (value in field.choices) {
            val node = bean()
            node.protocolOptionsJson = SudokuOptions.set(SudokuOptions.root(node),field,value).toString()
            NodeJson.checkStorage(node)
            val copied = node.clone()
            val entity = ProxyEntity().putBean(copied)
            val restored = KryoConverters.deserialize(ProxyEntity(),KryoConverters.serialize(entity)).requireBean() as SudokuBean
            assertEquals(node.protocolOptionsJson,restored.protocolOptionsJson)
            assertEquals(value,SudokuOptions.text(SudokuOptions.root(restored),field))
            val raw = SudokuOptions.raw(SudokuOptions.root(restored),field.path)
            if (value == "32") assertNull(raw) else {
                assertTrue(raw!!.asJsonPrimitive.isNumber)
                assertEquals(value.toInt(),raw.asInt)
            }
            val imported = SudokuOptions.importOutbound(SudokuOptions.outboundJson(restored).toString())!!
            assertEquals(restored.protocolOptionsJson,imported.protocolOptionsJson)
            fixture("allow-ui-$value",restored)
        }
        assertEquals(original,untouched.protocolOptionsJson)
        assertEquals("32",SudokuOptions.text(SudokuOptions.root(untouched),field))
    }

    @Test fun upstreamUriStaysUnchangedAndLocalPoliciesUseLosslessSnFallback() {
        val original = bean()
        val uri = SudokuShareLink.export(original)!!
        val imported = SudokuShareLink.parse(uri)
        assertEquals("32",SudokuOptions.text(SudokuOptions.root(imported),field))
        assertFalse(SudokuOptions.root(imported).has("table_limit"))
        fixture("allow-uri-default",imported)
        for (limit in listOf("64","128","256","512","0")) {
            val node = imported.clone()
            node.protocolOptionsJson = SudokuOptions.set(SudokuOptions.root(node),field,limit).toString()
            assertNull(SudokuShareLink.export(node))
            val sn = parseUniversal(node.toUniversalLink()) as SudokuBean
            assertEquals(node.protocolOptionsJson,sn.protocolOptionsJson)
            assertEquals(limit,SudokuOptions.text(SudokuOptions.root(sn),field))
            fixture("allow-sn-$limit",sn)
        }
        assertNotNull(SudokuShareLink.export(original))
    }

    @Test fun jsonTakeoverAndInvalidRepresentationsRemainUnmodified() {
        val node = bean()
        node.protocolOptionsJson = SudokuOptions.set(SudokuOptions.root(node),field,"64").toString()
        val json = SudokuOptions.outboundJson(node).apply { addProperty("table_limit",128); addProperty("tag","proxy") }.toString()
        NodeJson.set(node,NodeJson.OUTBOUND,json)
        val before = NodeJson.state(node)
        node.protocolOptionsJson = SudokuOptions.set(SudokuOptions.root(node),field,"0").toString()
        assertEquals(before,NodeJson.state(node.clone()))
        assertEquals(json,(parseUniversal(node.toUniversalLink()) as SudokuBean).customOutboundJson)
        assertNull(SudokuShareLink.export(node))
        fixture("allow-json-overrides-native",node,json)
        for (raw in listOf("""{"table_limit":null}""","""{"table_limit":"64"}""")) {
            val configured = bean().apply { protocolOptionsJson = raw }
            assertFalse(SudokuOptions.editable(SudokuOptions.root(configured),field))
            assertEquals(raw,configured.clone().protocolOptionsJson)
        }
    }
}
