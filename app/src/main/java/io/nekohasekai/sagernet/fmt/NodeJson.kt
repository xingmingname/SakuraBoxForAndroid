package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

object NodeJson {
    private val prettyGson by lazy { Gson().newBuilder().setPrettyPrinting().disableHtmlEscaping().create() }
    const val LEGACY = -1
    const val NONE = 0
    const val OUTBOUND = 1
    const val FULL = 2
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_PROFILE_BYTES = 1024 * 1024
    enum class ErrorKind { SYNTAX, STRUCTURE, UNSUPPORTED, MISSING, REFERENCE, ENVIRONMENT, CORE }
    class Invalid(val kind: ErrorKind, message: String) : IllegalArgumentException(message)
    data class State(val mode: Int, val outbound: String, val full: String, val legacyMask: Int = 0)
    fun state(bean: AbstractBean): State {
        val mode = bean.jsonMode ?: NONE
        if (mode !in setOf(LEGACY, NONE, OUTBOUND, FULL)) throw Invalid(ErrorKind.UNSUPPORTED, "Unknown JSON mode")
        return State(mode, bean.customOutboundJson.orEmpty(), bean.customConfigJson.orEmpty(), bean.jsonLegacyMask ?: 0)
    }
    fun enabled(bean: AbstractBean) = state(bean).let { it.mode in OUTBOUND..FULL || it.mode == LEGACY && (it.outbound.isNotBlank() || it.full.isNotBlank()) }
    fun legacyOutbound(bean: AbstractBean) = if (bean.jsonMode == LEGACY) bean.customOutboundJson.orEmpty() else ""
    fun legacyFull(bean: AbstractBean) = if (bean.jsonMode == LEGACY) bean.customConfigJson.orEmpty() else ""
    fun saved(bean: AbstractBean, mode: Int) = if (mode == OUTBOUND) bean.customOutboundJson.orEmpty() else bean.customConfigJson.orEmpty()
    fun copyState(from: AbstractBean, to: AbstractBean) {
        to.customOutboundJson = from.customOutboundJson; to.customConfigJson = from.customConfigJson
        to.jsonMode = from.jsonMode; to.jsonLegacyMask = from.jsonLegacyMask
    }
    fun set(bean: AbstractBean, mode: Int, text: String) {
        require(mode == OUTBOUND || mode == FULL)
        if (mode == OUTBOUND) bean.customOutboundJson = text else bean.customConfigJson = text
        bean.jsonMode = mode
        bean.jsonLegacyMask = (bean.jsonLegacyMask ?: 0) and mode.inv()
    }

    fun checkStorage(bean: AbstractBean) {
        if (KryoConverters.serialize(bean).size > MAX_PROFILE_BYTES)
            throw Invalid(ErrorKind.STRUCTURE, "The complete saved node, including both JSON documents, must fit within 1 MiB")
    }

    fun parse(text: String): JsonObject {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES)
            throw Invalid(ErrorKind.STRUCTURE, "JSON exceeds 2 MiB")
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; if (depth > 64) throw Invalid(ErrorKind.STRUCTURE, "JSON exceeds 64 levels") }; '}', ']' -> depth-- }
        }
        val element = try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                fun value(): JsonElement = when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (has(name)) throw Invalid(ErrorKind.STRUCTURE, "Duplicate JSON key at ${reader.path}")
                            add(name, value())
                        }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> com.google.gson.JsonArray().apply {
                        reader.beginArray(); while (reader.hasNext()) add(value()); reader.endArray()
                    }
                    JsonToken.STRING -> com.google.gson.JsonPrimitive(reader.nextString())
                    JsonToken.NUMBER -> {
                        val number = reader.nextString()
                        if (number.length > 1024) throw Invalid(ErrorKind.STRUCTURE, "Numeric literal is too long")
                        com.google.gson.JsonPrimitive(number.toBigDecimal())
                    }
                    JsonToken.BOOLEAN -> com.google.gson.JsonPrimitive(reader.nextBoolean())
                    JsonToken.NULL -> { reader.nextNull(); com.google.gson.JsonNull.INSTANCE }
                    else -> throw Invalid(ErrorKind.SYNTAX, "Expected JSON value at ${reader.path}")
                }
                val result = value()
                if (reader.peek() != JsonToken.END_DOCUMENT) throw Invalid(ErrorKind.SYNTAX, "Expected one JSON value at ${reader.path}")
                result
            }
        } catch (e: Invalid) { throw e } catch (e: Exception) {
            val location = Regex("line \\d+ column \\d+ path [^\\s]+?").find(e.message.orEmpty())?.value.orEmpty()
            throw Invalid(ErrorKind.SYNTAX, "Malformed JSON $location")
        }
        if (element == null || !element.isJsonObject) throw Invalid(ErrorKind.STRUCTURE, "$: expected JSON object")
        return element.asJsonObject
    }

    fun text(root: JsonObject) = prettyGson.toJson(root)
    fun string(root: JsonObject, key: String): String? {
        val v = root.get(key) ?: return null
        if (!v.isJsonPrimitive || !v.asJsonPrimitive.isString) throw Invalid(ErrorKind.STRUCTURE, "$key: expected string")
        return v.asString
    }

    fun outbound(text: String, tag: String): JsonObject = parse(text).apply {
        if (has("inbounds") || has("outbounds") || has("route")) throw Invalid(ErrorKind.STRUCTURE, "Expected one outbound, not a full configuration")
        val type = string(this, "type").orEmpty()
        if (type.isBlank()) throw Invalid(ErrorKind.MISSING, "outbound.type is required")
        addProperty("tag", tag) // Routing identity belongs to the profile/chain, not to its editable JSON.
    }

    fun validateReferences(root: JsonObject) {
        fun objects(key: String): List<JsonObject> {
            val value = root.get(key) ?: return emptyList()
            if (!value.isJsonArray) throw Invalid(ErrorKind.STRUCTURE, "$key: expected array")
            return value.asJsonArray.mapIndexed { index, item ->
                if (!item.isJsonObject) throw Invalid(ErrorKind.STRUCTURE, "$key[$index]: expected object")
                item.asJsonObject.apply { if (string(this, "type").isNullOrBlank()) throw Invalid(ErrorKind.MISSING, "$key[$index].type is required") }
            }
        }
        val outs = objects("outbounds") + objects("endpoints")
        if (outs.isEmpty()) throw Invalid(ErrorKind.MISSING, "outbounds/endpoints: at least one outbound is required")
        objects("inbounds")
        val tags = linkedSetOf<String>()
        outs.forEachIndexed { i, out ->
            val tag = string(out, "tag") ?: i.toString()
            if (!tags.add(tag)) throw Invalid(ErrorKind.REFERENCE, "Duplicate outbound tag: $tag")
            if (string(out, "type") in setOf("vless", "vmess", "trojan", "shadowsocks", "anytls", "hysteria", "hysteria2", "tuic", "http", "socks", "ssh")) {
                if (string(out, "server").isNullOrBlank()) throw Invalid(ErrorKind.MISSING, "outbounds[$i].server is required")
                val port = out.get("server_port")
                val hopping = string(out, "type") in setOf("hysteria", "hysteria2") && out.has("server_ports")
                if (!hopping && (port == null || !port.isJsonPrimitive || !port.asJsonPrimitive.isNumber ||
                    port.asBigDecimal.stripTrailingZeros().scale() > 0 || port.asBigDecimal < 1.toBigDecimal() || port.asBigDecimal > 65535.toBigDecimal())
                )
                    throw Invalid(ErrorKind.MISSING, "outbounds[$i].server_port: expected integer 1..65535")
            }
            if (string(out, "type") in setOf("vless", "vmess", "tuic") && string(out, "uuid").isNullOrBlank())
                throw Invalid(ErrorKind.MISSING, "outbounds[$i].uuid is required")
        }
        fun reference(value: String?, path: String) {
            if (!value.isNullOrBlank() && value !in tags) throw Invalid(ErrorKind.REFERENCE, "$path: unknown outbound $value")
        }
        val route = root.get("route")
        if (route != null) {
            if (!route.isJsonObject) throw Invalid(ErrorKind.STRUCTURE, "route: expected object")
            reference(string(route.asJsonObject, "final"), "route.final")
            fun rules(value: JsonElement?) {
                if (value == null) return
                if (!value.isJsonArray) throw Invalid(ErrorKind.STRUCTURE, "route.rules: expected array")
                value.asJsonArray.forEach { item ->
                    if (!item.isJsonObject) throw Invalid(ErrorKind.STRUCTURE, "route.rules: expected object")
                    reference(string(item.asJsonObject, "outbound"), "route.rules.outbound")
                    rules(item.asJsonObject.get("rules"))
                }
            }
            rules(route.asJsonObject.get("rules"))
        }
        val graph = mutableMapOf<String, MutableList<String>>()
        outs.forEachIndexed { i, out ->
            val tag = string(out, "tag") ?: i.toString()
            val detour = string(out, "detour")
            reference(detour, "$tag.detour"); if (detour != null) graph.getOrPut(tag) { mutableListOf() }.add(detour)
            if (string(out, "type") in setOf("selector", "urltest")) {
                val members = out.get("outbounds")
                if (members == null || !members.isJsonArray || members.asJsonArray.size() == 0) throw Invalid(ErrorKind.MISSING, "$tag.outbounds is required")
                members.asJsonArray.forEach {
                    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw Invalid(ErrorKind.STRUCTURE, "$tag.outbounds: expected strings")
                    reference(it.asString, "$tag.outbounds")
                    graph.getOrPut(tag) { mutableListOf() }.add(it.asString)
                }
                reference(string(out, "default"), "$tag.default")
                if (string(out, "default")?.let { default -> members.asJsonArray.none { it.asString == default } } == true)
                    throw Invalid(ErrorKind.REFERENCE, "$tag.default must belong to its outbounds")
            }
        }
        val visited = hashSetOf<String>(); val visiting = hashSetOf<String>()
        fun visit(tag: String) {
            if (tag in visited) return
            if (!visiting.add(tag)) throw Invalid(ErrorKind.REFERENCE, "Outbound reference cycle")
            if (visiting.size > 256) throw Invalid(ErrorKind.REFERENCE, "Outbound reference chain is too deep")
            graph[tag]?.forEach(::visit)
            visiting.remove(tag); visited.add(tag)
        }
        graph.keys.forEach(::visit)
    }

    fun fullTestOutbound(root: JsonObject): JsonObject {
        val selected = previewOutbound(root)
        if (string(selected, "type") in setOf("selector", "urltest", "direct", "block", "dns"))
            throw Invalid(ErrorKind.REFERENCE, "JSON_TEST_TARGET")
        return selected
    }

    fun previewOutbound(root: JsonObject): JsonObject {
        val final = root.getAsJsonObject("route")?.let { string(it, "final") }
        val outs = listOf("outbounds", "endpoints").flatMap { root.getAsJsonArray(it)?.map { item -> item.asJsonObject } ?: emptyList() }
        val selected = if (!final.isNullOrBlank()) outs.singleOrNull { string(it, "tag") == final } else outs.singleOrNull()
        if (selected == null)
            throw Invalid(ErrorKind.REFERENCE, "JSON_TEST_TARGET")
        return selected
    }

    fun runtimeTag(root: JsonObject): String = root.getAsJsonObject("route")?.let { string(it, "final") }?.takeIf { it.isNotBlank() }
        ?: listOf("outbounds", "endpoints").firstNotNullOfOrNull { root.getAsJsonArray(it)?.firstOrNull()?.asJsonObject }
            ?.let { string(it, "tag") } ?: "0"

    fun tcpTarget(bean: AbstractBean): Pair<String, Int> {
        val out = when (bean.jsonMode) {
            OUTBOUND -> parse(bean.customOutboundJson)
            FULL -> fullTestOutbound(parse(bean.customConfigJson))
            else -> return bean.serverAddress to bean.serverPort
        }
        val address = string(out, "server")
        val port = out.get("server_port")
        if (address.isNullOrBlank() || port == null || !port.isJsonPrimitive || !port.asJsonPrimitive.isNumber ||
            port.asBigDecimal.stripTrailingZeros().scale() > 0 || port.asBigDecimal < 1.toBigDecimal() || port.asBigDecimal > 65535.toBigDecimal())
            throw Invalid(ErrorKind.REFERENCE, "JSON_TEST_TARGET")
        return address to port.asInt
    }

    fun testConfig(root: JsonObject): JsonObject = root.deepCopy().apply {
        val target = fullTestOutbound(this)
        add("inbounds", com.google.gson.JsonArray())
        remove("experimental")
        remove("services")
        add("log", JsonObject().apply { addProperty("disabled", true) })
        val route = getAsJsonObject("route") ?: JsonObject().also { add("route", it) }
        if (route.get("auto_detect_interface")?.asBoolean == false)
            throw Invalid(ErrorKind.ENVIRONMENT, "URL Test requires route.auto_detect_interface=true for safe network binding")
        route.addProperty("auto_detect_interface", true)
        route.addProperty("final", string(target, "tag") ?: "0")
        route.add("rules", com.google.gson.JsonArray())
    }

    fun requireVpn(root: JsonObject, vpn: Boolean, physicalInterfaces: Set<String> = emptySet()) {
        val tuns = root.getAsJsonArray("inbounds")?.filter { string(it.asJsonObject, "type") == "tun" }.orEmpty()
        if (vpn && tuns.size != 1)
            throw Invalid(ErrorKind.MISSING, "VPN mode requires a tun inbound; choose proxy service mode for a proxy-only configuration")
        if (!vpn && tuns.isNotEmpty()) throw Invalid(ErrorKind.ENVIRONMENT, "A tun inbound requires the Android VPN service mode")
        for (tun in tuns.map { it.asJsonObject }) {
            if (strings(tun, "address").isEmpty()) throw Invalid(ErrorKind.MISSING, "tun.address is required by the Android VPN interface")
            if (strings(tun, "include_package").isNotEmpty() && strings(tun, "exclude_package").isNotEmpty())
                throw Invalid(ErrorKind.STRUCTURE, "Android VPN cannot combine include_package and exclude_package")
            for (field in listOf("include_uid", "exclude_uid", "include_uid_range", "exclude_uid_range", "include_android_user",
                "include_interface", "exclude_interface", "include_mac_address", "exclude_mac_address", "auto_redirect", "netns", "loopback_address"))
                if (tun.has(field) && tun.get(field).toString() !in setOf("false", "null", "[]", "\"\""))
                    throw Invalid(ErrorKind.UNSUPPORTED, "tun.$field is not implemented by this Android VPN bridge")
            val http = tun.getAsJsonObject("platform")?.getAsJsonObject("http_proxy")
            if (http?.get("enabled")?.asBoolean == true && strings(http, "match_domain").isNotEmpty())
                throw Invalid(ErrorKind.UNSUPPORTED, "tun.platform.http_proxy.match_domain is not supported by Android ProxyInfo")
            if (tun.get("auto_route")?.asBoolean == true) requireAndroidProtection(root, physicalInterfaces)
        }
    }

    fun requireLegacyVpnProtection(root: JsonObject, vpn: Boolean, physicalInterfaces: Set<String> = emptySet()) {
        // The legacy Android bridge installs app routes even when tun.auto_route is false.
        if (vpn && root.getAsJsonArray("inbounds")?.any { string(it.asJsonObject, "type") == "tun" } == true)
            requireAndroidProtection(root, physicalInterfaces)
    }

    private fun requireAndroidProtection(root: JsonObject, physicalInterfaces: Set<String>) {
        val route = root.getAsJsonObject("route")
        val defaultInterface = route?.let { string(it, "default_interface") }.orEmpty()
        val automatic = defaultInterface.isEmpty() && route?.get("auto_detect_interface")?.asBoolean == true
        val defaultBound = defaultInterface in physicalInterfaces
        val dialerTypes = setOf("direct", "socks", "http", "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "anytls", "ssh", "shadowtls", "wireguard",
            "udp", "tcp", "tls", "https", "quic", "h3", "dhcp")
        fun check(options: JsonObject, path: String) {
            if (!string(options, "detour").isNullOrEmpty()) return
            val bound = string(options, "bind_interface").orEmpty()
            val sourceBound = listOf("inet4_bind_address", "inet6_bind_address").any {
                options.has(it) && !options[it].isJsonNull
            }
            // Source-address binding disables the core's automatic Android protect callback.
            val protected = if (bound.isNotEmpty()) bound in physicalInterfaces else !sourceBound && (automatic || defaultBound)
            if (!protected) throw Invalid(ErrorKind.ENVIRONMENT,
                "$path: TUN auto_route requires route.auto_detect_interface=true without source-address overrides, " +
                    "or a currently available non-VPN bind_interface/default_interface; protect_path alone cannot verify Android protection")
        }
        var dialers = 0
        fun visit(value: JsonElement, path: String) {
            if (value.isJsonObject) {
                val obj = value.asJsonObject
                val legacyDns = path.startsWith("dns.servers[") && !obj.has("type") &&
                    string(obj, "address") !in setOf(null, "local", "fakeip")
                if (legacyDns || string(obj, "type") in dialerTypes || obj.has("bind_interface") ||
                    obj.has("inet4_bind_address") || obj.has("inet6_bind_address") || obj.has("protect_path")) {
                    check(obj, path); dialers++
                }
                obj.entrySet().filter { it.key !in setOf("tls", "transport") }
                    .forEach { (key, child) -> visit(child, "$path.$key") }
            } else if (value.isJsonArray) value.asJsonArray.forEachIndexed { i, child -> visit(child, "$path[$i]") }
        }
        // Only outbound/endpoint and DNS dialers open upstream sockets here; transports inherit them.
        for (key in listOf("outbounds", "endpoints")) root.get(key)?.let { visit(it, key) }
        root.getAsJsonObject("dns")?.get("servers")?.let { visit(it, "dns.servers") }
        if (dialers == 0 && !automatic && !defaultBound) check(JsonObject(), "route")
    }

    fun strings(root: JsonObject, key: String): List<String> {
        val item = root.get(key) ?: return emptyList()
        if (item.isJsonNull) return emptyList()
        val items = if (item.isJsonArray) item.asJsonArray.toList() else listOf(item)
        return items.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) throw Invalid(ErrorKind.STRUCTURE, "$key: expected string or string array")
            it.asString
        }
    }

    // Global patches may replace the outbound list; an explicit node source still owns its entry.
    fun restoreOutbounds(config: MutableMap<String, Any?>, sources: Map<String, Map<String, Any?>>) {
        for ((tag, source) in sources) {
            val key = if (source["type"] == "wireguard") "endpoints" else "outbounds"
            val other = if (key == "endpoints") "outbounds" else "endpoints"
            (config[other] as? List<*>)?.let { list -> config[other] = list.filter { (it as? Map<*, *>)?.get("tag") != tag } }
            val entries = (config[key] as? List<*>)?.toMutableList() ?: mutableListOf()
            val index = entries.indexOfFirst { (it as? Map<*, *>)?.get("tag") == tag }
            if (index < 0) entries.add(source) else entries[index] = source
            config[key] = entries
        }
    }
}
