package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NodeJsonTextQualityTest {
    private fun legacy(root: JsonObject) = Gson().newBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)

    @Test fun formattingRetainsExactExistingOutputAndDoesNotMutateTheDocument() {
        for (text in listOf("{}", """{"zero":0,"flag":false,"blank":"","null":null,"unicode":"樱花🌸","escape":"<>&='\\\""}""",
            """{"outbounds":[{"type":"direct","tag":"proxy"}],"unknown":{"nested":[0,false,"",null,1.2500]}}""")) {
            val root = NodeJson.parse(text); val original = root.deepCopy()
            assertEquals(legacy(root), NodeJson.text(root))
            assertEquals(original, root)
        }
    }

    @Test fun sharedFormatterKeepsConcurrentDocumentsIndependent() {
        val executor = Executors.newFixedThreadPool(6)
        try {
            val jobs = (0 until 48).map { id -> Callable {
                val root = JsonObject().apply { addProperty("id", id); addProperty("text", "synthetic-$id".repeat(100)); addProperty("flag", id % 2 == 0) }
                val expected = legacy(root)
                repeat(20) { assertEquals(expected, NodeJson.text(root)); assertEquals(root, NodeJson.parse(NodeJson.text(root))) }
            } }
            executor.invokeAll(jobs).forEach { it.get() }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun largeDocumentsStillRoundTripAndInputGuardsRemainActive() {
        val root = JsonObject().apply { addProperty("text", "x".repeat(512 * 1024)); addProperty("zero", 0); addProperty("flag", false) }
        assertEquals(legacy(root), NodeJson.text(root)); assertEquals(root, NodeJson.parse(NodeJson.text(root)))
        for (invalid in listOf("""{"a":1,"a":2}""", "{".repeat(66), """{"text":"${"x".repeat(NodeJson.MAX_BYTES)}"}""")) {
            try { NodeJson.parse(invalid); fail("Invalid document was accepted") } catch (_: NodeJson.Invalid) { }
        }
    }
}
