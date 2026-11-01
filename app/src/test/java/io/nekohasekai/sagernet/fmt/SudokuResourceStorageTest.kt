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
import java.util.Base64
import kotlinx.coroutines.runBlocking
import io.nekohasekai.sagernet.ktx.parseProxies

class SudokuResourceStorageTest {
    private fun options(count: Int, mode: String = "up_ascii_down_entropy") = JsonObject().apply {
        addProperty("key", "f03-synthetic-key")
        addProperty("ascii", mode)
        addProperty("padding_min", 5); addProperty("padding_max", 15)
        add("custom_tables", JsonArray().apply { repeat(count) { add("xpxvvpvv") } })
    }
    private fun bean(root: JsonObject) = SudokuBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
        name = "F03 合成节点"; protocolOptionsJson = root.toString()
    }
    private fun fixture(name: String, current: SudokuBean, json: String? = null) {
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
            val dir = File(directory, "f03").apply { mkdirs() }
            File(dir, "$name.json").writeText(json ?: SudokuOptions.outboundJson(current).toString())
        }
    }
    private fun retained(current: SudokuBean, count: Int) {
        NodeJson.checkStorage(current)
        val copy = current.clone()
        assertEquals(current.protocolOptionsJson, copy.protocolOptionsJson)
        assertEquals(count, SudokuOptions.root(copy)["custom_tables"].asJsonArray.size())
        val entity = ProxyEntity().putBean(copy)
        assertEquals(ProxyEntity.TYPE_SUDOKU, entity.type)
        assertEquals(current.protocolOptionsJson, (KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(entity)).requireBean() as SudokuBean).protocolOptionsJson)
    }

    @Test fun oversizedUriUiSnAndJsonKeepOriginalDataWithoutASaveGate() {
        val root = options(4000)
        val ui = bean(JsonObject())
        val tableField = SudokuOptions.fields.single { it.path == "custom_tables" }
        ui.protocolOptionsJson = SudokuOptions.set(options(0), tableField, List(4000) { "xpxvvpvv" }.joinToString("\n")).toString()
        retained(ui, 4000); fixture("reject-ui", ui)

        val payload = JsonObject().apply {
            addProperty("h", "127.0.0.1"); addProperty("p", 443)
            addProperty("k", root["key"].asString); addProperty("a", root["ascii"].asString)
            add("ts", root["custom_tables"].deepCopy())
        }
        val link = "sudoku://" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
        assertTrue(link.length < SudokuShareLink.MAX_URI_LENGTH)
        val uri = SudokuShareLink.parse(link)
        retained(uri, 4000); fixture("reject-uri", uri)
        val shared = SudokuShareLink.export(uri)!!
        assertEquals(uri.protocolOptionsJson, SudokuShareLink.parse(shared).protocolOptionsJson)

        val sn = parseUniversal(uri.toUniversalLink()) as SudokuBean
        retained(sn, 4000); fixture("reject-sn", sn)
        val imported = SudokuOptions.importOutbound(SudokuOptions.outboundJson(ui).toString())!!
        retained(imported, 4000); fixture("reject-internal-json", imported)
        val override = bean(root)
        val json = SudokuOptions.outboundJson(ui).apply { addProperty("tag", "proxy") }.toString()
        NodeJson.set(override, NodeJson.OUTBOUND, json)
        val restored = override.clone()
        assertEquals(json, restored.customOutboundJson)
        assertTrue(NodeJson.enabled(restored))
        assertNull(SudokuShareLink.export(restored))
        assertEquals(json, (parseUniversal(restored.toUniversalLink()) as SudokuBean).customOutboundJson)
        fixture("reject-json-outbound", restored, json)
        val full = """{"outbounds":[$json],"route":{"final":"proxy"}}"""
        NodeJson.set(override, NodeJson.FULL, full)
        assertEquals(full, override.clone().customConfigJson)
        fixture("reject-json-full", override, full)
    }

    @Test fun runtimeBoundariesDoNotBecomeStorageLimits() {
        for ((count, mode, name) in listOf(
            Triple(32,"prefer_ascii","allow-single-32"), Triple(33,"prefer_ascii","reject-single-33"),
            Triple(16,"up_ascii_down_entropy","allow-directional-16"), Triple(17,"up_ascii_down_entropy","reject-directional-17")
        )) {
            val current = bean(options(count,mode))
            retained(current,count); fixture(name,current)
        }
    }

    @Test fun mixedImportKeepsOversizedOriginalAndNormalNode() = runBlocking {
        val link = SudokuShareLink.export(bean(options(4000)))!!
        val imported = parseProxies("$link\nsocks://127.0.0.1:1080#normal")
        assertEquals(2, imported.size)
        retained(imported.filterIsInstance<SudokuBean>().single(),4000)
    }

    @Test fun jsonStorageSkipsOnlyTheDecodedSudokuRuntimeBudgetFailure() {
        var constructionCalled = false
        NodeJsonProfile.checkCoreForStorage {
            fun decodedCheck(): Unit = throw Exception("outbounds[0]: SUDOKU_TABLE_RESOURCE_LIMIT: table entry count exceeds budget")
            decodedCheck()
            constructionCalled = true
        }
        assertFalse(constructionCalled)
        NodeJsonProfile.checkCoreForStorage { constructionCalled = true }
        assertTrue(constructionCalled)
        for (message in listOf(
            "decode config: unknown field SUDOKU_TABLE_RESOURCE_LIMIT",
            "outbounds[0]: decode key: SUDOKU_TABLE_RESOURCE_LIMIT: invalid encoding",
            "missing key", "unknown type", "permission denied", "SUDOKU_TABLE_RESOURCE_LIMIT: user text"
        )) {
            val original = Exception(message)
            assertSame(original, assertThrows(Exception::class.java) {
                NodeJsonProfile.checkCoreForStorage { throw original }
            })
        }
    }
}
