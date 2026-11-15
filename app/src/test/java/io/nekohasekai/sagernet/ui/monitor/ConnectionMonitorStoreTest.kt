package io.nekohasekai.sagernet.ui.monitor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionMonitorStoreTest {
    private fun row(id: String, domain: String = "", ip: String = "2001:db8::2", created: Long = 10): String {
        val connection = JsonObject().apply {
            addProperty("id", id); addProperty("domain", domain); addProperty("destination", ip)
            addProperty("source", "::1"); addProperty("sourcePort", 1234); addProperty("port", 443)
            addProperty("network", "udp"); addProperty("createdAt", created); addProperty("duration", 100)
            addProperty("upload", 20); addProperty("download", 30); addProperty("outbound", "proxy")
            add("chain", JsonArray().apply { add("direct"); add("proxy") })
            addProperty("rule", "domain=test.example")
            add("packages", JsonArray().apply { add("test.package") })
        }
        return JsonObject().apply { addProperty("type", "connection"); add("connection", connection) }.toString()
    }
    private fun status(count: Int, memory: Long? = null) = JsonObject().apply {
        addProperty("type", "status"); addProperty("count", count)
        memory?.let { addProperty("memory", it) }
    }.toString()
    private fun message(type: String) = JsonObject().apply { addProperty("type", type) }.toString()

    @Test fun initialZeroAndUnavailableMemoryAreDistinct() {
        val store = ConnectionMonitorStore()
        assertFalse(store.snapshot().available)
        assertTrue(store.accept(status(0)))
        assertTrue(store.snapshot().available)
        assertEquals(0, store.snapshot().count)
        assertNull(store.snapshot().memory)
        store.accept(status(0, 123456))
        assertEquals(123456L, store.snapshot().memory)
    }

    @Test fun stableIDsDoNotDuplicateAndCountersDoNotReorder() {
        val store = ConnectionMonitorStore()
        store.accept(row("b", created = 2))
        store.accept(row("a", created = 1))
        store.accept(row("b", created = 2))
        store.accept(status(2))
        assertEquals(listOf("a", "b"), store.snapshot().connections.map { it.id })
        val traffic = JsonObject().apply {
            addProperty("type", "traffic")
            add("traffic", JsonArray().apply { add(JsonObject().apply {
                addProperty("id", "a"); addProperty("upload", 200); addProperty("download", 300); addProperty("duration", 500)
            }) })
        }
        store.accept(traffic.toString()); store.accept(status(2))
        assertEquals(listOf("a", "b"), store.snapshot().connections.map { it.id })
        assertEquals(200L, store.snapshot().connections[0].upload)
        assertEquals(500L, store.snapshot().connections[0].duration)
    }

    @Test fun unknownTrafficCannotResurrectClosedConnections() {
        val store = ConnectionMonitorStore()
        store.accept(row("a"))
        val close = JsonObject().apply { addProperty("type", "closed"); addProperty("id", "a") }
        store.accept(close.toString()); store.accept(close.toString())
        val traffic = JsonObject().apply { addProperty("type", "traffic"); add("traffic", JsonArray().apply {
            add(JsonObject().apply { addProperty("id", "a"); addProperty("upload", 999) })
        }) }
        store.accept(traffic.toString()); store.accept(status(0))
        assertTrue(store.snapshot().connections.isEmpty())
        assertNull(store.snapshot().closed)
    }

    @Test fun detailKeepsFinalCountersAndDurationWithoutMarkingItActive() {
        val store = ConnectionMonitorStore("a")
        store.accept(row("a"))
        store.accept(row("unrelated"))
        assertEquals(1, store.snapshot().connections.size)
        val connection = com.google.gson.JsonParser.parseString(row("a")).asJsonObject.getAsJsonObject("connection")
        connection.addProperty("duration", 7000)
        connection.addProperty("upload", 777)
        val close = JsonObject().apply {
            addProperty("type", "closed"); addProperty("id", "a"); addProperty("closedAt", 10000)
            add("connection", connection)
        }
        store.accept(close.toString()); store.accept(status(1))
        val state = store.snapshot()
        assertTrue(state.connections.isEmpty())
        assertEquals(777L, state.closed?.upload)
        assertEquals(7000L, state.closed?.duration)
        assertEquals(10000L, state.closedAt)
        store.accept(status(0))
        assertEquals(7000L, store.snapshot().closed?.duration)
    }

    @Test fun missingFinalSampleDoesNotPresentOldCountersAsFinal() {
        val store = ConnectionMonitorStore("a")
        store.accept(row("a"))
        store.accept(JsonObject().apply { addProperty("type", "closed"); addProperty("id", "a") }.toString())
        store.accept(status(0))
        assertTrue(store.snapshot().connections.isEmpty())
        assertNull(store.snapshot().closed)
        assertNull(store.snapshot().closedAt)
    }

    @Test fun restartClearsEveryOldValueAndClosedDetail() {
        val store = ConnectionMonitorStore("a")
        store.accept(row("a", "recorded.example")); store.accept(status(1, 5000))
        store.accept(message("reset"))
        val state = store.snapshot()
        assertTrue(state.connections.isEmpty())
        assertNull(state.closed)
        assertNull(state.memory)
        assertNull(state.count)
        assertFalse(state.available)
        store.accept(status(0))
        assertEquals(0, store.snapshot().count)
    }

    @Test fun nativeFieldsAndNoDomainFallbackArePreserved() {
        val store = ConnectionMonitorStore()
        store.accept(row("a")); store.accept(row("b", "原始.example", ""))
        store.accept(status(2))
        val values = store.snapshot().connections
        assertEquals("", values[0].domain)
        assertEquals("2001:db8::2", values[0].destination)
        assertEquals("::1", values[0].source)
        assertEquals(1234, values[0].sourcePort)
        assertEquals(listOf("direct", "proxy"), values[0].chain)
        assertEquals(listOf("test.package"), values[0].packages)
        assertEquals("原始.example", values[1].domain)
        assertEquals("", values[1].destination)
    }

    @Test fun manyShortConnectionsRemainUniqueAndAreRemoved() {
        val store = ConnectionMonitorStore()
        repeat(10000) { store.accept(row(it.toString(), created = it.toLong())) }
        store.accept(status(10000))
        assertEquals(10000, store.snapshot().connections.size)
        repeat(10000) {
            store.accept(JsonObject().apply { addProperty("type", "closed"); addProperty("id", it.toString()) }.toString())
        }
        store.accept(status(0))
        assertTrue(store.snapshot().connections.isEmpty())
    }

    @Test fun ipv6AndDurationFormattingDoNotChangeNetworkSemantics() {
        assertEquals("[2001:db8::1]:443", monitorEndpoint("2001:db8::1", 443))
        assertEquals("192.0.2.1:80", monitorEndpoint("192.0.2.1", 80))
        assertEquals("", monitorEndpoint("", 443))
        assertEquals("example.test:443", monitorEndpoint("example.test", 443))
        assertEquals("0:00:00", monitorDuration(-1))
        assertEquals("25:01:01", monitorDuration(90061000))
    }
}
