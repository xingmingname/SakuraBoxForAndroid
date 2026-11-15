package io.nekohasekai.sagernet.ui.monitor

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class MonitoredConnection(
    val id: String,
    val domain: String,
    val destination: String,
    val port: Int,
    val source: String,
    val sourcePort: Int,
    val network: String,
    val createdAt: Long,
    val duration: Long,
    val upload: Long,
    val download: Long,
    val outbound: String,
    val chain: List<String>,
    val rule: String,
    val packages: List<String>,
    val ownerUid: Int? = null,
)

data class ConnectionMonitorState(
    val connected: Boolean? = null,
    val available: Boolean = false,
    val count: Int? = null,
    val memory: Long? = null,
    val connections: List<MonitoredConnection> = emptyList(),
    val closed: MonitoredConnection? = null,
    val closedAt: Long? = null,
)

/** Only touched by the reader coroutine. A status frame commits a batch. */
class ConnectionMonitorStore(private val detailID: String? = null) {
    private val entries = LinkedHashMap<String, MonitoredConnection>()
    private var order = emptyList<String>()
    private var orderChanged = true
    private var closed: MonitoredConnection? = null
    private var closedAt: Long? = null
    private var count: Int? = null
    private var memory: Long? = null
    private var ready = false

    fun accept(line: String): Boolean {
        val message = JsonParser.parseString(line).asJsonObject
        when (message.text("type")) {
            "reset" -> {
                entries.clear()
                order = emptyList()
                orderChanged = true
                closed = null
                closedAt = null
                count = null
                memory = null
                ready = false
            }
            "connection" -> {
                val value = decode(message.getAsJsonObject("connection"))
                if (detailID == null || value.id == detailID) {
                    if (!entries.containsKey(value.id)) orderChanged = true
                    entries[value.id] = value
                    if (closed?.id == value.id) { closed = null; closedAt = null }
                }
            }
            "closed" -> {
                val id = message.text("id")
                val previous = entries.remove(id)
                if (previous != null) orderChanged = true
                if (id == detailID) {
                    closed = message.getAsJsonObject("connection")?.let(::decode)
                    closedAt = message.number("closedAt").takeIf { it > 0 }
                }
            }
            "traffic" -> {
                message.getAsJsonArray("traffic")?.forEach {
                    val item = it.asJsonObject
                    val id = item.text("id")
                    entries[id]?.let { previous ->
                        entries[id] = previous.copy(
                            upload = item.number("upload"),
                            download = item.number("download"),
                            duration = item.number("duration"),
                        )
                    }
                }
            }
            "status" -> {
                count = message.number("count").toInt()
                memory = message.get("memory")?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it > 0 }
                ready = true
                return true
            }
        }
        return false
    }

    fun snapshot(): ConnectionMonitorState {
        if (orderChanged) {
            order = entries.values.sortedWith(compareBy<MonitoredConnection> { it.createdAt }.thenBy { it.id }).map { it.id }
            orderChanged = false
        }
        return ConnectionMonitorState(
            connected = true, available = ready, count = count, memory = memory,
            connections = order.mapNotNull(entries::get), closed = closed, closedAt = closedAt,
        )
    }

    private fun decode(value: JsonObject) = MonitoredConnection(
        id = value.text("id").also { require(it.isNotEmpty()) },
        domain = value.text("domain"), destination = value.text("destination"),
        port = value.number("port").toInt(),
        source = value.text("source"), sourcePort = value.number("sourcePort").toInt(),
        network = value.text("network"), createdAt = value.number("createdAt"),
        duration = value.number("duration"), upload = value.number("upload"), download = value.number("download"),
        outbound = value.text("outbound"), chain = value.strings("chain"),
        rule = value.text("rule"), packages = value.strings("packages"),
        ownerUid = value.number("ownerUid").toInt().takeIf { it > 1000 },
    )
}

private fun JsonObject.text(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
private fun JsonObject.number(key: String): Long = get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0
private fun JsonObject.strings(key: String): List<String> = getAsJsonArray(key)?.map { it.asString }.orEmpty()
