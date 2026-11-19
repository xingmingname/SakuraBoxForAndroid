package io.nekohasekai.sagernet.fmt.sudoku

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import moe.matsuri.nb4a.SingBoxOptions

/** Presence-preserving fields from libcore/sudoku/options.go and option.DialerOptions.
 * This editor stores configuration; runtime validity remains the core's decision. */
object SudokuOptions {
    /** One Activity's read-only editor snapshot. All edits use set/setSelection copies. */
    internal class EditorCache(private val parse: (String?) -> JsonObject = ProtocolOptions::parse) {
        private var nodeId = 0L
        private var text: String? = null
        private var root: JsonObject? = null

        @Synchronized fun read(nodeId: Long, text: String?): JsonObject {
            root?.let { if (this.nodeId == nodeId && this.text == text) return it }
            clear() // A failed parse must never expose the previous node/content.
            val parsed = parse(text)
            this.nodeId = nodeId
            this.text = text
            root = parsed
            return parsed
        }

        @Synchronized fun clear() {
            root = null
            text = null
            nodeId = 0L
        }
    }

    enum class Kind { TEXT, SECRET, INTEGER, BOOLEAN, CHOICE, LIST, MARK, RESOLVER, TABLE_LIMIT }
    enum class Group { PROTOCOL, HTTPMASK, LEGACY, DIAL, RESOLVER }
    data class Field(val path: String, val kind: Kind, val group: Group,
                     val choices: List<String> = emptyList())
    private fun field(path: String, kind: Kind, group: Group, vararg choices: String) = Field(path, kind, group, choices.toList())
    val asciiModes = listOf("prefer_ascii", "prefer_entropy", "ascii", "entropy",
        "up_ascii_down_entropy", "up_entropy_down_ascii", "up_ascii_down_ascii", "up_entropy_down_entropy")
    val fields: List<Field> = buildList {
        val p = Group.PROTOCOL
        add(field("key", Kind.SECRET, p))
        add(field("aead", Kind.CHOICE, p, "chacha20-poly1305", "aes-128-gcm", "none"))
        add(Field("ascii", Kind.CHOICE, p, asciiModes))
        add(field("custom_table", Kind.TEXT, p)); add(field("custom_tables", Kind.LIST, p))
        add(field("table_limit", Kind.TABLE_LIMIT, p, "32", "64", "128", "256", "512", "0"))
        add(field("padding_min", Kind.INTEGER, p)); add(field("padding_max", Kind.INTEGER, p))
        add(field("enable_pure_downlink", Kind.BOOLEAN, p))
        add(field("multiplex", Kind.CHOICE, p, "off", "auto", "on"))
        val h = Group.HTTPMASK
        add(field("httpmask.disable", Kind.BOOLEAN, h))
        add(field("httpmask.mode", Kind.CHOICE, h, "legacy", "stream", "poll", "auto", "ws"))
        add(field("httpmask.tls", Kind.BOOLEAN, h)); add(field("httpmask.host", Kind.TEXT, h))
        add(field("httpmask.path_root", Kind.TEXT, h))
        add(field("httpmask.multiplex", Kind.CHOICE, h, "off", "auto", "on"))
        val l = Group.LEGACY
        add(field("disable_http_mask", Kind.BOOLEAN, l)); add(field("http_mask_tls", Kind.BOOLEAN, l))
        add(field("http_mask_mode", Kind.CHOICE, l, "legacy", "stream", "poll", "auto", "ws"))
        add(field("http_mask_multiplex", Kind.CHOICE, l, "off", "auto", "on"))
        add(field("http_mask_host", Kind.TEXT, l)); add(field("http_mask_path_root", Kind.TEXT, l))
        add(field("http_mask_strategy", Kind.CHOICE, l, "random", "post", "websocket"))
        add(field("path_root", Kind.TEXT, l))
        val d = Group.DIAL
        listOf("detour", "bind_interface", "inet4_bind_address", "inet6_bind_address", "protect_path", "netns",
            "connect_timeout", "tcp_keep_alive", "tcp_keep_alive_interval", "fallback_delay").forEach { add(field(it, Kind.TEXT, d)) }
        listOf("bind_address_no_port", "reuse_addr", "tcp_fast_open", "tcp_multi_path", "disable_tcp_keep_alive", "udp_fragment")
            .forEach { add(field(it, Kind.BOOLEAN, d)) }
        add(field("routing_mark", Kind.MARK, d))
        add(field("network_strategy", Kind.CHOICE, d, "default", "fallback", "hybrid"))
        add(field("network_type", Kind.LIST, d, "wifi", "cellular", "ethernet", "other"))
        add(field("fallback_network_type", Kind.LIST, d, "wifi", "cellular", "ethernet", "other"))
        add(field("domain_strategy", Kind.CHOICE, d, "as_is", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"))
        val r = Group.RESOLVER
        add(field("domain_resolver", Kind.RESOLVER, r))
        add(field("domain_resolver.timeout", Kind.TEXT, r))
        add(field("domain_resolver.strategy", Kind.CHOICE, r, "as_is", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"))
        add(field("domain_resolver.disable_cache", Kind.BOOLEAN, r))
        add(field("domain_resolver.disable_optimistic_cache", Kind.BOOLEAN, r))
        add(field("domain_resolver.rewrite_ttl", Kind.INTEGER, r))
        add(field("domain_resolver.client_subnet", Kind.TEXT, r))
    }

    fun root(bean: SudokuBean): JsonObject = ProtocolOptions.parse(bean.protocolOptionsJson)

    fun retainedFields(root: JsonObject): List<String> {
        val known = fields.map { it.path }.toSet() + "domain_resolver.server"
        val retained = mutableListOf<String>()
        fun visit(value: JsonObject, prefix: String) {
            for ((key, child) in value.entrySet()) {
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                if (path !in known && known.none { it.startsWith("$path.") }) retained += path
                else if (child.isJsonObject) visit(child.asJsonObject, path)
            }
        }
        visit(root, "")
        return retained
    }

    fun raw(root: JsonObject, path: String): JsonElement? {
        var value: JsonElement = root
        for (key in path.split('.')) {
            if (!value.isJsonObject) return null
            value = value.asJsonObject.get(key) ?: return null
        }
        return value
    }

    fun editable(root: JsonObject, field: Field): Boolean {
        val parents = field.path.split('.').dropLast(1)
        for (i in parents.indices) {
            val path = parents.take(i + 1).joinToString(".")
            val parent = raw(root, path) ?: continue
            if (path == "domain_resolver" && parent.isJsonPrimitive && parent.asJsonPrimitive.isString) continue
            if (!parent.isJsonObject) return false
        }
        val value = raw(root, field.path) ?: return true
        if (value.isJsonNull) return false // Keep explicit null without presenting it as an omitted field.
        return when (field.kind) {
            Kind.BOOLEAN -> value.isJsonPrimitive && value.asJsonPrimitive.isBoolean
            Kind.INTEGER -> value.isJsonPrimitive && (value.asJsonPrimitive.isNumber || value.asJsonPrimitive.isString)
            Kind.TABLE_LIMIT -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber && value.asString.toLongOrNull() != null
            Kind.LIST -> if (value.isJsonArray) value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString && '\n' !in it.asString && '\r' !in it.asString } &&
                !(value.asJsonArray.size() == 1 && value.asJsonArray[0].asString.isEmpty()) &&
                (field.choices.isEmpty() || value.asJsonArray.map { it.asString }.let { values -> values.none { it.isEmpty() } && values.distinct().size == values.size })
                else field.choices.isNotEmpty() && value.isJsonPrimitive && value.asJsonPrimitive.isString &&
                    value.asString.isNotEmpty() && '\n' !in value.asString && '\r' !in value.asString
            Kind.MARK -> value.isJsonPrimitive && (value.asJsonPrimitive.isString || value.asJsonPrimitive.isNumber)
            Kind.RESOLVER -> value.isJsonPrimitive && value.asJsonPrimitive.isString || value.isJsonObject &&
                (value.asJsonObject.get("server")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isString } != false)
            else -> value.isJsonPrimitive && value.asJsonPrimitive.isString
        }
    }

    fun text(root: JsonObject, field: Field): String {
        val value = raw(root, field.path) ?: return if (field.kind == Kind.TABLE_LIMIT) "32" else ""
        if (field.kind == Kind.RESOLVER && value.isJsonObject) return value.asJsonObject.get("server")?.asString.orEmpty()
        return when {
            value.isJsonArray -> value.asJsonArray.joinToString("\n") { it.asString }
            value.isJsonPrimitive -> value.asString
            else -> ""
        }
    }

    /** Called only for the control the user changed. Unedited data is never normalized. */
    fun set(root: JsonObject, field: Field, input: String?): JsonObject {
        require(editable(root, field)) { "Retained JSON value is not editable with this control" }
        val result = root.deepCopy()
        if (field.kind == Kind.RESOLVER) {
            val existing = result.get("domain_resolver")
            if (existing?.isJsonObject == true) {
                if (input.isNullOrEmpty()) existing.asJsonObject.remove("server") else existing.asJsonObject.addProperty("server", input)
            } else if (input.isNullOrEmpty()) result.remove("domain_resolver") else result.addProperty("domain_resolver", input)
            return result
        }
        val parts = field.path.split('.')
        var target = result
        for (part in parts.dropLast(1)) {
            var child = target.get(part)
            if (part == "domain_resolver" && child?.isJsonPrimitive == true) {
                val server = child
                child = JsonObject().apply { add("server", server) }; target.add(part, child)
            }
            if (child == null) {
                if (input == null) return result
                child = JsonObject(); target.add(part, child)
            }
            target = child.asJsonObject
        }
        val value = when {
            input == null -> null
            field.kind == Kind.LIST -> JsonArray().apply { if (input.isNotEmpty()) input.split('\n').forEach { add(it) } }
            input.isEmpty() -> null
            field.kind == Kind.TABLE_LIMIT && input == "32" -> null // Omitted policy is the per-node default.
            field.kind == Kind.TABLE_LIMIT -> input.toBigDecimalOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(input)
            field.kind == Kind.INTEGER -> input.toBigDecimalOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(input)
            field.kind == Kind.MARK -> input.toBigDecimalOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(input)
            field.kind == Kind.BOOLEAN -> JsonPrimitive(input == "true")
            else -> JsonPrimitive(input)
        }
        if (value == null) target.remove(parts.last()) else target.add(parts.last(), value)
        // Keep even empty objects rather than normalizing unedited stored data.
        return result
    }

    /** A multi-select control must not reorder an imported list or materialize an omitted value. */
    fun setSelection(root: JsonObject, field: Field, selected: Set<String>): JsonObject {
        require(field.kind == Kind.LIST && field.choices.isNotEmpty() && editable(root, field))
        val value = raw(root, field.path)
        val previous = if (value?.isJsonArray == true) value.asJsonArray.map { it.asString }
            else value?.let { listOf(it.asString) }.orEmpty()
        if (previous.toSet() == selected) return root.deepCopy()
        val ordered = (previous + field.choices + selected.sorted()).distinct().filter { it in selected }
        return set(root, field, ordered.joinToString("\n"))
    }

    fun buildOutbound(bean: SudokuBean) = SingBoxOptions.Outbound().apply {
        type = "sudoku"
        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
    }

    fun retainHostBeforeAddressRewrite(bean: SudokuBean) {
        if (NodeJson.enabled(bean)) return
        val stored = root(bean)
        val nested = stored.get("httpmask")?.takeIf { it.isJsonObject }?.asJsonObject
        val disable = if (nested != null) nested.get("disable") else stored.get("disable_http_mask")
        if (disable?.isJsonPrimitive == true && disable.asJsonPrimitive.isBoolean && disable.asBoolean) return
        val target = nested ?: stored
        val key = if (nested == null) "http_mask_host" else "host"
        val host = target.get(key)
        if (host != null && (!host.isJsonPrimitive || !host.asJsonPrimitive.isString || host.asString.isNotBlank())) return
        target.addProperty(key, bean.serverAddress)
        bean.protocolOptionsJson = stored.toString()
    }

    fun outboundJson(bean: SudokuBean): JsonObject = root(bean).apply {
        addProperty("type", "sudoku"); addProperty("server", bean.serverAddress); addProperty("server_port", bean.serverPort)
    }

    fun importOutbound(text: String): SudokuBean? {
        val json = NodeJson.parse(text)
        if (NodeJson.string(json, "type") != "sudoku") return null
        val bean = SudokuBean().apply { initializeDefaultValues() }
        val address = json.get("server")
        val port = json.get("server_port")
        val portInt = if (port?.isJsonPrimitive == true && port.asJsonPrimitive.isNumber) port.asString.toIntOrNull() else null
        if (address?.isJsonPrimitive != true || !address.asJsonPrimitive.isString || address.asString.isBlank() || portInt == null) {
            // Unsupported base-field representations remain a real Sudoku node with explicit
            // JSON takeover, rather than being coerced or silently dropped by ordinary UI.
            NodeJson.set(bean, NodeJson.OUTBOUND, text)
        } else {
            bean.serverAddress = address.asString; bean.serverPort = portInt
        }
        bean.name = json.get("tag")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
        listOf("type", "tag", "server", "server_port").forEach { json.remove(it) }
        bean.protocolOptionsJson = json.toString()
        return bean
    }
}
