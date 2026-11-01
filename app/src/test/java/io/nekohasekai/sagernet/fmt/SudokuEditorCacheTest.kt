package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.fmt.sudoku.SudokuShareLink
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SudokuEditorCacheTest {
    private fun field(path: String) = SudokuOptions.fields.single { it.path == path }
    private fun bean(text: String) = SudokuBean().apply {
        initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
        protocolOptionsJson = text
    }
    private fun populated(): String {
        var root = JsonObject()
        for (field in SudokuOptions.fields) {
            val value = when (field.kind) {
                SudokuOptions.Kind.BOOLEAN -> "false"
                SudokuOptions.Kind.INTEGER, SudokuOptions.Kind.MARK -> "0"
                SudokuOptions.Kind.TABLE_LIMIT -> "64"
                SudokuOptions.Kind.CHOICE -> field.choices.first()
                SudokuOptions.Kind.LIST -> field.choices.firstOrNull() ?: "xpxvvpvv"
                else -> "synthetic-f02-value"
            }
            root = SudokuOptions.set(root, field, value)
        }
        return root.toString()
    }

    // Replays the old Activity's reads, including its short-circuited unset-control check.
    private fun previousPageReads(read: () -> JsonObject) {
        SudokuOptions.editable(read(), field("key"))
        for (field in SudokuOptions.fields.filter { it.path != "key" }) {
            SudokuOptions.editable(read(), field)
            val raw = SudokuOptions.raw(read(), field.path)
            if (field.kind in setOf(SudokuOptions.Kind.BOOLEAN, SudokuOptions.Kind.LIST) && raw != null) {
                SudokuOptions.editable(read(), field)
            }
        }
        SudokuOptions.retainedFields(read())
    }

    @Test fun repeatedProductionFieldReadsReallyDeserializeOnce() {
        for (text in listOf("{}", populated())) {
            var before = 0
            previousPageReads { before++; ProtocolOptions.parse(text) }
            var after = 0
            val cache = SudokuOptions.EditorCache { after++; ProtocolOptions.parse(it) }
            previousPageReads { cache.read(7, text) }
            assertEquals(if (text == "{}") 104 else 120, before)
            assertEquals(1, after)
            // Further summaries and refreshes of the same document are cache hits too.
            repeat(4) { previousPageReads { cache.read(7, text) } }
            assertEquals(1, after)
            println("F02 parse calls: ${if (text == "{}") "empty-object" else "52-fields"} before=$before after=$after; repeated refreshes add=0")
        }
    }

    @Test fun nodeTextAndLifecycleEachInvalidateTheSingleEntry() {
        var calls = 0
        val cache = SudokuOptions.EditorCache { calls++; ProtocolOptions.parse(it) }
        val text = """{"key":"synthetic-f02-key","table_limit":0}"""
        val first = cache.read(1, text)
        assertSame(first, cache.read(1, String(text.toCharArray())))
        assertEquals(1, calls)
        val nextNode = cache.read(2, text)
        assertNotSame(first, nextNode)
        val nextText = cache.read(2, " $text ")
        assertNotSame(nextNode, nextText)
        assertEquals(nextNode, nextText)
        assertEquals(3, calls)
        cache.clear()
        assertNotSame(nextText, cache.read(2, " $text "))
        assertEquals(4, calls)
        // Returning to an old node reparses rather than retaining a per-node map.
        assertNotSame(first, cache.read(1, text))
        assertEquals(5, calls)
    }

    @Test fun malformedAndNonObjectJsonNeverReturnOrRetainAnOlderSnapshot() {
        var calls = 0
        val cache = SudokuOptions.EditorCache { calls++; ProtocolOptions.parse(it) }
        val valid = """{"key":"synthetic-f02-key"}"""
        for (bad in listOf("{", "[]", "null", "true")) {
            val previous = cache.read(1, valid)
            val before = calls
            assertThrows(RuntimeException::class.java) { cache.read(1, bad) }
            assertThrows(RuntimeException::class.java) { cache.read(1, bad) }
            assertEquals(before + 2, calls) // No cached success or fallback for invalid input.
            assertNotSame(previous, cache.read(1, valid))
        }
    }

    @Test fun blankNullAndLegacyRepresentationsKeepExistingParsingAndStorageRules() {
        val cache = SudokuOptions.EditorCache()
        for (raw in listOf(null, "", " \n", "{}")) {
            assertEquals(JsonObject(), cache.read(0, raw))
        }
        for (raw in listOf("""{"padding_min":0,"enable_pure_downlink":false,"key":""}""",
            """{"table_limit":null,"custom_tables":[],"httpmask":null,"future":{"keep":true}}""")) {
            val original = bean(raw)
            val root = cache.read(1, original.protocolOptionsJson)
            assertEquals(ProtocolOptions.parse(raw), root)
            assertEquals(raw, original.clone().protocolOptionsJson)
            assertEquals(root, SudokuOptions.root(original.clone()))
        }
    }

    @Test fun setAndSelectionCannotMutateTheSharedReadSnapshot() {
        val cache = SudokuOptions.EditorCache()
        val raw = """{"key":"synthetic-f02-key","padding_min":0,"network_type":["wifi"],"future":{"keep":true}}"""
        val root = cache.read(4, raw)
        val before = root.deepCopy()
        val edit = SudokuOptions.set(root, field("padding_min"), "1")
        val selection = SudokuOptions.setSelection(root, field("network_type"), setOf("wifi", "cellular"))
        assertNotEquals(root, edit); assertNotEquals(root, selection)
        assertEquals(before, root)
        assertSame(root, cache.read(4, raw))
    }

    @Test fun updateReadsExternalChangesInsteadOfTheOlderRenderedDocument() {
        val cache = SudokuOptions.EditorCache()
        val rendered = cache.read(8, """{"key":"old","table_limit":64}""")
        var stored = """{"key":"new","table_limit":128,"padding_min":0,"enable_pure_downlink":false,"future":true}"""
        val updated = SudokuOptions.set(cache.read(8, stored), field("padding_max"), "0")
        stored = updated.toString()
        val reopened = cache.read(8, stored)
        assertEquals("new", reopened["key"].asString)
        assertEquals(128, reopened["table_limit"].asInt)
        assertEquals(0, reopened["padding_min"].asInt)
        assertEquals(0, reopened["padding_max"].asInt)
        assertFalse(reopened["enable_pure_downlink"].asBoolean)
        assertTrue(reopened["future"].asBoolean)
        assertEquals("old", rendered["key"].asString)
    }

    @Test fun sixLimitsReopenCopyShareAndJsonTakeoverRemainIndependent() {
        val cache = SudokuOptions.EditorCache()
        for ((index, value) in field("table_limit").choices.withIndex()) {
            val node = bean("""{"key":"synthetic-f02-key","aead":"none","ascii":"prefer_entropy","padding_min":5,"padding_max":15}""")
            val id = index.toLong() + 1
            node.protocolOptionsJson = SudokuOptions.set(cache.read(id, node.protocolOptionsJson), field("table_limit"), value).toString()
            NodeJson.checkStorage(node)
            cache.clear()
            val restored = KryoConverters.sudokuDeserialize(KryoConverters.serialize(node.clone()))!!
            assertEquals(value, SudokuOptions.text(cache.read(id, restored.protocolOptionsJson), field("table_limit")))
            val sn = parseUniversal(restored.toUniversalLink()) as SudokuBean
            assertEquals(restored.protocolOptionsJson, sn.protocolOptionsJson)
            if (value == "32") assertNotNull(SudokuShareLink.export(restored)) else assertNull(SudokuShareLink.export(restored))
            val override = SudokuOptions.outboundJson(node).apply { addProperty("key", "json-key") }.toString()
            NodeJson.set(node, NodeJson.OUTBOUND, override)
            val state = NodeJson.state(node)
            node.protocolOptionsJson = SudokuOptions.set(cache.read(id, node.protocolOptionsJson), field("padding_min"), "0").toString()
            assertEquals(state, NodeJson.state(node.clone()))
            assertEquals(override, (parseUniversal(node.toUniversalLink()) as SudokuBean).customOutboundJson)
            node.jsonMode = NodeJson.NONE
            assertEquals(value, SudokuOptions.text(cache.read(id, node.protocolOptionsJson), field("table_limit")))
        }
    }

    @Test fun concurrentReadsAndClearKeepEachRequestedContentAssociatedWithItsNode() {
        val calls = AtomicInteger()
        val cache = SudokuOptions.EditorCache { calls.incrementAndGet(); ProtocolOptions.parse(it) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            pool.invokeAll((1..8).map { Callable {
                repeat(200) { assertEquals(7, cache.read(7, "{\"node\":7}")["node"].asInt) }
            } }).forEach { it.get() }
            assertEquals(1, calls.get())
            pool.invokeAll((1..8).map { node -> Callable {
                repeat(100) {
                    if (it % 11 == 0) cache.clear()
                    assertEquals(node, cache.read(node.toLong(), "{\"node\":$node}")["node"].asInt)
                }
            } }).forEach { it.get() }
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
