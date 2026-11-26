package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import java.math.BigDecimal

/** Native outbound fields stored in the versioned common bean extension. */
object ProtocolOptions {
    enum class Kind { TEXT, PEM, DURATION, INTEGER, BOOLEAN, LIST, PINS, CHOICE }
    data class Field(val path: String, val kind: Kind, val min: Long = 0,
                     val max: Long = Int.MAX_VALUE.toLong(), val choices: List<String> = emptyList())
    fun protocol(bean: AbstractBean): String? = ProtocolOptionFields.protocol(bean)
    fun isNativeQuic(bean: AbstractBean) = ProtocolOptionFields.isNativeQuic(bean)
    fun supportsCustomAlpn(bean: AbstractBean) = ProtocolOptionFields.supportsCustomAlpn(bean)
    fun supportsSharedTransport(bean: AbstractBean?) = ProtocolOptionFields.supportsSharedTransport(bean)
    fun editableField(bean: AbstractBean, path: String) = ProtocolOptionFields.editableField(bean, path)

    /** Run on an editor copy, never on a database object. The old extension had final priority. */
    fun migrateEditorOptions(bean: AbstractBean) {
        if (bean !is HysteriaBean || protocol(bean) != "hysteria") return
        val root = try { parse(bean.protocolOptionsJson) } catch (_: Exception) { return }
        val old = get(root, "disable_path_mtu_discovery") ?: return
        if (!old.isJsonPrimitive || !old.asJsonPrimitive.isBoolean) return // Preserve invalid data for the existing validation UI.
        bean.disableMtuDiscovery = old.asBoolean
        root.remove("disable_path_mtu_discovery")
        bean.protocolOptionsJson = if (root.size() == 0) "" else root.toString()
    }

    /** A copy for generated configuration. Hidden legacy data remains intact in the saved bean. */
    fun effectiveOptions(bean: AbstractBean, root: JsonObject): JsonObject {
        if (bean is io.nekohasekai.sagernet.fmt.sudoku.SudokuBean) return root.deepCopy()
        if (bean is io.nekohasekai.sagernet.fmt.nakis.NakisBean) return io.nekohasekai.sagernet.fmt.nakis.NakisOptions.effectiveOptions(root)
        var result = ConnectionSecurity.effectiveOptions(bean, root)
        fun omit(path: String) {
            result = set(result, Field(path, Kind.TEXT), "")
        }
        if (isNativeQuic(bean)) omit("tls.ech.query_server_name")
        if (protocol(bean) in setOf("vless", "vmess", "trojan")) omit("transport.permit_without_stream")
        if (bean is HttpBean) {
            result.remove("multiplex")
            result.remove("transport") // HTTP outbound path/headers are top-level and remain supported.
        }
        if (protocol(bean) == "hysteria2") {
            result = set(result, Field("tls.alpn", Kind.LIST), "h3")
        }
        if (result.get("tls")?.isJsonObject == true)
            MtlsOptions.omitBlankInline(result.getAsJsonObject("tls"))
        return result
    }

    fun fields(bean: AbstractBean): List<Field> = ProtocolOptionFields.fields(bean)

    fun parse(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject()
        val value = JsonParser.parseString(raw)
        require(value.isJsonObject) { "Outbound override must be a JSON object" }
        return value.asJsonObject
    }

    fun get(root: JsonObject, path: String): JsonElement? {
        var current: JsonElement = root
        for (key in path.split('.')) {
            if (!current.isJsonObject) return null
            current = current.asJsonObject.get(key) ?: return null
        }
        return current.takeUnless { it.isJsonNull }
    }

    fun text(root: JsonObject, field: Field): String {
        val value = get(root, field.path) ?: return ""
        return when {
            value.isJsonArray -> value.asJsonArray.joinToString("\n") { if (it.isJsonPrimitive) it.asString else it.toString() }
            value.isJsonPrimitive -> value.asString
            else -> value.toString()
        }
    }

    /** A deliberately bounded subset of Go duration syntax, converted to nanoseconds for validation only. */
    fun durationNanos(value: String): BigDecimal {
        require(value.length <= 128) { "Duration is too long" }
        if (value == "0") return BigDecimal.ZERO // Go's explicit default/reset duration.
        val factors = mapOf("ns" to "1", "us" to "1000", "µs" to "1000", "μs" to "1000", "ms" to "1000000",
            "s" to "1000000000", "m" to "60000000000", "h" to "3600000000000")
        val pattern = Regex("([0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)")
        var end = 0
        var total = BigDecimal.ZERO
        for (match in pattern.findAll(value)) {
            require(match.range.first == end) { "Use a positive duration with units, e.g. 30s or 1m30s" }
            total += match.groupValues[1].toBigDecimal() * factors.getValue(match.groupValues[2]).toBigDecimal()
            end = match.range.last + 1
        }
        require(end == value.length && end > 0 && (total.signum() == 0 || total >= BigDecimal.ONE) && total <= Long.MAX_VALUE.toBigDecimal()) {
            "Use a positive duration within the core time.Duration range"
        }
        return total
    }

    fun value(field: Field, input: String): JsonElement? {
        val v = if (field.kind == Kind.PEM) input else input.trim()
        if (v.isBlank()) return null // Omitted, not a forced zero/false.
        if (field.path.startsWith("tls.") && field.kind != Kind.BOOLEAN) return when (field.kind) {
            Kind.LIST, Kind.PINS -> com.google.gson.JsonArray().apply {
                v.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
            }
            else -> JsonPrimitive(v)
        }
        return when (field.kind) {
            Kind.PEM -> JsonPrimitive(v)
            Kind.DURATION -> { durationNanos(v); JsonPrimitive(v) }
            Kind.INTEGER -> {
                require(v.matches(Regex("[0-9]+"))) { "Use a non-negative whole number" }
                val n = v.toLongOrNull()
                require(n != null && n in field.min..field.max) { "Value must be in ${field.min}..${field.max}" }
                JsonPrimitive(n)
            }
            Kind.BOOLEAN -> { require(v == "true" || v == "false") { "Choose true, false or core default" }; JsonPrimitive(v.toBoolean()) }
            Kind.CHOICE -> { require(v in field.choices) { "Unsupported value for ${field.path}" }; JsonPrimitive(v) }
            Kind.LIST, Kind.PINS -> {
                val parts = v.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                require(parts.isNotEmpty()) { "Empty list" }
                require(field.choices.isEmpty() || parts.all { it in field.choices }) { "Unsupported list value for ${field.path}" }
                com.google.gson.JsonArray().apply { parts.forEach { add(it) } }
            }
            Kind.TEXT -> {
                require(v.none { it.code < 32 || it.code == 127 }) { "Control characters are not allowed in ${field.path}" }
                if (field.path == "client_metadata") require(v.toByteArray(Charsets.UTF_8).size <= 1024) { "Client metadata must not exceed 1024 UTF-8 bytes" }
                if (field.path == "transport.method") require(v.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) { "Invalid HTTP method" }
                JsonPrimitive(v)
            }
        }
    }

    /** Preserve every other key, including unknown custom advanced settings. Never coerce a scalar parent. */
    fun set(root: JsonObject, field: Field, input: String): JsonObject {
        val result = root.deepCopy()
        val parts = field.path.split('.')
        var target = result
        val parents = mutableListOf<Pair<JsonObject, String>>()
        val value = value(field, input)
        for (part in parts.dropLast(1)) {
            val child = target.get(part)
            require(child == null || child.isJsonObject) { "${field.path}: parent $part must be an object; use the JSON editor" }
            if (child == null && value == null) return result
            val obj = child?.asJsonObject ?: JsonObject().also { target.add(part, it) }
            parents += target to part
            target = obj
        }
        if (value == null) target.remove(parts.last()) else target.add(parts.last(), value)
        for ((parent, key) in parents.asReversed()) {
            if (parent.getAsJsonObject(key).size() == 0) parent.remove(key) else break
        }
        return result
    }

    internal fun validationOutbound(bean: AbstractBean, stored: JsonObject): JsonObject? {
        val candidate = bean.clone().apply { protocolOptionsJson = stored.toString() }
        val outbound = when (candidate) {
            is AnyTLSBean -> moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean(candidate)
            is ShadowTLSBean -> moe.matsuri.nb4a.SingBoxOptions.Outbound_ShadowTLSOptions().apply {
                type = "shadowtls"
                tls = io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundTLS(candidate, false)
            }
            is StandardV2RayBean -> io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean(candidate, false)
            is HysteriaBean -> {
                if (!isNativeQuic(candidate)) return null
                io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean(candidate, false)
            }
            is TuicBean -> {
                if (!isNativeQuic(candidate)) return null
                io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean(candidate, false)
            }
            else -> return null
        }
        applyTo(outbound, candidate)
        outbound._hack_custom_config = NodeJson.legacyOutbound(candidate)
        return com.google.gson.Gson().toJsonTree(outbound.asMap()).asJsonObject
    }

    fun supportsTlsCurvePreferences(bean: AbstractBean, stored: JsonObject): Boolean = try {
        val tls = validationOutbound(bean, stored)?.getAsJsonObject("tls")
        tls != null && get(tls, "enabled")?.asBoolean == true &&
            get(tls, "engine")?.asString.orEmpty() in setOf("", "go") &&
            get(tls, "utls.enabled")?.asBoolean != true &&
            get(tls, "reality.enabled")?.asBoolean != true
    } catch (_: Exception) { false }

    fun hasAdvancedOptions(bean: AbstractBean): Boolean =
        ConnectionSecurity.hasAdditionalOptions(bean) ||
            (bean is StandardV2RayBean && ConnectionSecurity.mode(bean) == ConnectionSecurity.Mode.REALITY &&
                (bean is HttpBean || bean is VMessBean && !bean.isVLESS)) ||
            try { fields(bean).any { get(parse(NodeJson.legacyOutbound(bean)), it.path) != null } } catch (_: Exception) { false }

    fun applyTo(outbound: moe.matsuri.nb4a.SingBoxOptions.SingBoxOption, bean: AbstractBean, external: Boolean = false) {
        if (external) {
            require(bean.protocolOptionsJson.isNullOrBlank()) { "Native protocol advanced options cannot be used with an external plugin; clear protocol advanced options first" }
            return
        }
        val root = parse(bean.protocolOptionsJson)
        // Built-in DTO -> protocol extension -> existing explicit user override (serializer).
        val values: Map<String, Any?> = com.google.gson.GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create().fromJson(effectiveOptions(bean, root),
            object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type)
        moe.matsuri.nb4a.utils.Util.mergeMap(outbound._hack_config_map, values)
    }
}
