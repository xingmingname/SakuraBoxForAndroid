package io.nekohasekai.sagernet.fmt.nakis

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import moe.matsuri.nb4a.SingBoxOptions

object NakisOptions {
    private val baseKeys = setOf("type", "tag", "server", "server_port")
    private val nativeOptionKeys = setOf(
        "uuid", "server_alternates", "tls", "sni", "tls_pins", "ech_config",
        "pool_lo", "pool_hi", "pool_max_uses", "pool_max_age", "race_count",
        "dial_timeout", "handshake_timeout", "keepalive", "congestion",
        "send_pack", "send_tail", "send_quiet", "shape_up", "shape_wait",
        "half_close_timeout", "udp_jitter",
    )

    // Official defaults used for hints only. An empty field stays absent from outbound JSON.
    internal val editorDefaults = mapOf(
        "pool_lo" to "2", "pool_hi" to "32", "pool_max_uses" to "8", "pool_max_age" to "45s",
        "race_count" to "3", "dial_timeout" to "8s", "handshake_timeout" to "10s", "keepalive" to "15s",
        "send_pack" to "20480", "send_tail" to "512", "send_quiet" to "4", "shape_wait" to "20ms",
        "half_close_timeout" to "2m", "udp_jitter" to "8ms",
    )

    internal fun editorText(root: JsonObject, key: String, kind: ProtocolOptions.Kind): String =
        if (key == "shape_up") root.get(key)?.takeUnless { it.isJsonNull }?.toString().orEmpty()
        else ProtocolOptions.text(root, ProtocolOptions.Field(key, kind))

    internal fun isEditable(root: JsonObject, key: String): Boolean = when (key) {
        "sni", "tls_pins", "ech_config" -> root.get("tls") != JsonPrimitive(false)
        "shape_wait" -> root.get("shape_up")?.let { it.isJsonArray && it.asJsonArray.size() > 0 } == true
        else -> true
    }

    fun effectiveOptions(root: JsonObject): JsonObject = storedOptions(root).apply {
        for (key in listOf("sni", "tls_pins", "ech_config", "shape_wait")) {
            if (!isEditable(root, key)) remove(key)
        }
    }

    private fun storedOptions(root: JsonObject): JsonObject = JsonObject().apply {
        for ((key, value) in root.entrySet()) {
            if (key in nativeOptionKeys) add(key, value.deepCopy())
        }
    }

    fun buildOutbound(bean: NakisBean) = SingBoxOptions.Outbound().apply {
        type = "nakis"
        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
    }

    fun importOutbound(text: String): NakisBean? {
        val root = NodeJson.parse(text)
        if (NodeJson.string(root, "type") != "nakis") return null
        val address = NodeJson.string(root, "server")
        val port = root.get("server_port")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asString?.toIntOrNull()
        return NakisBean().apply {
            initializeDefaultValues()
            name = NodeJson.string(root, "tag").orEmpty()
            if (!address.isNullOrBlank() && port != null) {
                serverAddress = address
                serverPort = port
            }
            // Outbound JSON may contain sing-box dial options or extensions outside the native editor.
            if (address.isNullOrBlank() || port == null || root.keySet().any { it !in baseKeys && it !in nativeOptionKeys }) {
                NodeJson.set(this, NodeJson.OUTBOUND, text)
            }
            protocolOptionsJson = storedOptions(root).toString()
        }
    }

    internal fun parseValue(key: String, kind: ProtocolOptions.Kind, input: String): JsonElement? {
        require(key in nativeOptionKeys)
        // UUID is the shared secret. Keep the editor text byte-for-byte.
        if (key == "uuid") return input.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
        if (key == "shape_up") {
            if (input.isBlank()) return null
            val shape = NodeJson.parse("{\"value\":$input}").get("value")
            require(shape.isJsonArray && shape.asJsonArray.all { range ->
                range.isJsonArray && range.asJsonArray.size() == 2 &&
                    range.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString.toIntOrNull() != null }
            })
            return shape
        }
        val text = input.trim()
        if (text.isEmpty()) return null
        return when (kind) {
            ProtocolOptions.Kind.DURATION -> {
                NakisShareLink.durationNanos(text)
                JsonPrimitive(text)
            }
            ProtocolOptions.Kind.INTEGER -> JsonPrimitive(requireNotNull(text.toIntOrNull()))
            else -> ProtocolOptions.value(ProtocolOptions.Field(key, kind), input)
        }
    }
}
