package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import java.math.BigDecimal
import android.util.Base64

/** Native outbound fields stored in the versioned common bean extension. */
object ProtocolOptions {
    enum class Kind { TEXT, DURATION, INTEGER, BOOLEAN, LIST, PINS, CHOICE }
    data class Field(val path: String, val kind: Kind, val min: Long = 0,
                     val max: Long = Int.MAX_VALUE.toLong(), val choices: List<String> = emptyList())
    private fun duration(p: String) = Field(p, Kind.DURATION)
    private fun integer(p: String, min: Long = 0, max: Long = Int.MAX_VALUE.toLong()) = Field(p, Kind.INTEGER, min, max)
    private fun bool(p: String) = Field(p, Kind.BOOLEAN)
    private fun choice(p: String, vararg v: String) = Field(p, Kind.CHOICE, choices = v.toList())

    fun protocol(bean: AbstractBean): String? = when (bean) {
        is AnyTLSBean -> "anytls"
        is ShadowTLSBean -> "shadowtls"
        is HttpBean -> "http"
        is TrojanBean -> "trojan"
        is VMessBean -> if (bean.isVLESS) "vless" else "vmess"
        is HysteriaBean -> if (bean.protocol != HysteriaBean.PROTOCOL_UDP) null else if (bean.protocolVersion == 1) "hysteria" else "hysteria2"
        is TuicBean -> if (bean.protocolVersion == 5) "tuic" else null
        is SOCKSBean -> "socks"
        is ShadowsocksBean -> "shadowsocks" // Its supported SIP003 plugins are handled by the native SS builder.
        is SSHBean -> "ssh"
        is WireGuardBean -> "wireguard"
        else -> null // External plugins and raw ConfigBean retain their own editors.
    }

    fun isNativeQuic(bean: AbstractBean) = protocol(bean) in setOf("hysteria", "hysteria2", "tuic")
    fun supportsCustomAlpn(bean: AbstractBean) = protocol(bean) != "hysteria2"
    fun supportsSharedTransport(bean: AbstractBean?) = bean !is HttpBean

    /** Keep the legacy schema for reading old data; only expose controls implemented by this build. */
    fun editableField(bean: AbstractBean, path: String): Boolean = when {
        path == "tls.ech.query_server_name" && isNativeQuic(bean) -> false
        path == "transport.permit_without_stream" -> false // Build uses gRPC Lite, not with_grpc.
        path == "disable_path_mtu_discovery" && protocol(bean) == "hysteria" -> false
        else -> true
    }

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
        var result = root.deepCopy()
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
        return result
    }

    class QuicECHConfigException : IllegalArgumentException(
        "This core requires a static ECH CONFIGS PEM for QUIC; dynamic ECH DNS lookup is unsupported")

    private fun validateQuicECH(bean: AbstractBean, root: JsonObject) {
        if (!isNativeQuic(bean)) return
        // Explicit custom JSON still has final priority, including disabling ECH or supplying a file.
        val merged = root.deepCopy()
        fun merge(target: JsonObject, source: JsonObject) {
            for ((key, value) in source.entrySet()) {
                val existing = target.get(key)
                if (existing?.isJsonObject == true && value.isJsonObject) merge(existing.asJsonObject, value.asJsonObject)
                else target.add(key, value.deepCopy())
            }
        }
        merge(merged, parse(bean.customOutboundJson))
        if (get(merged, "tls.ech.enabled")?.asBoolean != true) return
        val config = get(merged, "tls.ech.config")
        if (!get(merged, "tls.ech.config_path")?.asString.isNullOrBlank() &&
            (config == null || config.isJsonArray && config.asJsonArray.size() == 0)) return // Core checks the file.
        val pem = when {
            config?.isJsonArray == true -> config.asJsonArray.joinToString("\n") { it.asString }
            config?.isJsonPrimitive == true -> config.asString
            else -> throw QuicECHConfigException()
        }.trim()
        val begin = "-----BEGIN ECH CONFIGS-----"
        val end = "-----END ECH CONFIGS-----"
        if (!pem.startsWith(begin) || !pem.endsWith(end)) throw QuicECHConfigException()
        val body = pem.removePrefix(begin).removeSuffix(end).filterNot { it.isWhitespace() }
        if (!body.matches(Regex("[A-Za-z0-9+/=]+"))) throw QuicECHConfigException()
        val bytes = try { Base64.decode(body, Base64.NO_WRAP) }
            catch (_: IllegalArgumentException) { throw QuicECHConfigException() }
        // ECHConfigList is a length-prefixed non-empty list; cryptographic validity remains the core's job.
        if (bytes.size < 6 || ((bytes[0].toInt() and 255) shl 8 or (bytes[1].toInt() and 255)) != bytes.size - 2)
            throw QuicECHConfigException()
    }

    fun fields(bean: AbstractBean): List<Field> {
        val p = protocol(bean) ?: return emptyList()
        val result = mutableListOf(duration("connect_timeout"))
        when (p) {
            "anytls" -> result += listOf(duration("idle_session_check_interval"), duration("idle_session_timeout"),
                integer("min_idle_session"), Field("client_metadata", Kind.TEXT))
            "vmess" -> result += listOf(bool("global_padding"), bool("authenticated_length"))
            "hysteria2" -> result += listOf(duration("hop_interval_max"),
                choice("bbr_profile", "standard", "conservative", "aggressive"), bool("disable_chrome_parrot"),
                choice("obfs.type", "salamander", "gecko"), integer("obfs.min_packet_size", 0, 2048), integer("obfs.max_packet_size", 0, 2048))
            "tuic" -> result += listOf(bool("udp_over_stream"), duration("heartbeat"))
            "ssh" -> result += listOf(Field("host_key_algorithms", Kind.LIST), Field("client_version", Kind.TEXT),
                Field("cipher", Kind.LIST), Field("mac", Kind.LIST), Field("kex_algorithm", Kind.LIST))
            "wireguard" -> result += listOf(integer("workers", 1), duration("udp_timeout"))
        }
        if (p in listOf("hysteria", "hysteria2", "tuic")) {
            result += listOf(duration("idle_timeout"), duration("keep_alive_period"),
                integer("max_concurrent_streams", 1), integer("initial_packet_size", 1200, 65527), bool("disable_path_mtu_discovery"))
            if (p != "hysteria") result += listOf(integer("stream_receive_window", 1, Long.MAX_VALUE),
                integer("connection_receive_window", 1, Long.MAX_VALUE))
        }
        if (p in listOf("vmess", "vless", "trojan", "shadowsocks")) {
            // Existing multiplex controls keep responsibility for enabled/protocol/padding.
            if (p == "shadowsocks") result += listOf(bool("multiplex.enabled"), choice("multiplex.protocol", "h2mux", "smux", "yamux"), bool("multiplex.padding"))
            result += listOf(integer("multiplex.max_connections", 1), integer("multiplex.min_streams", 1))
            if (p == "shadowsocks") result += integer("multiplex.max_streams", 1) // VMess/VLESS/Trojan already expose muxConcurrency.
        }
        if (bean is StandardV2RayBean && p in listOf("vmess", "vless", "trojan")) {
            when (bean.type) {
                "http" -> result += listOf(duration("transport.idle_timeout"), duration("transport.ping_timeout"), Field("transport.method", Kind.TEXT))
                "grpc" -> result += listOf(duration("transport.idle_timeout"), duration("transport.ping_timeout"), bool("transport.permit_without_stream"))
            }
        }
        if (p in listOf("anytls", "shadowtls", "http", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic")) {
            result += listOf(duration("tls.handshake_timeout"), Field("tls.certificate_public_key_sha256", Kind.PINS),
                Field("tls.ech.query_server_name", Kind.TEXT))
            if (p !in listOf("hysteria", "hysteria2", "tuic")) {
                result += listOf(choice("tls.min_version", "1.0", "1.1", "1.2", "1.3"),
                    choice("tls.max_version", "1.0", "1.1", "1.2", "1.3"), bool("tls.disable_sni"),
                    Field("tls.curve_preferences", Kind.LIST, choices = listOf("P256", "P384", "P521", "X25519", "X25519MLKEM768")))
            }
            // StandardV2Ray/AnyTLS already have native ECH editors. QUIC gets the same inline options.
            if (p in listOf("hysteria", "hysteria2", "tuic")) {
                result += listOf(bool("tls.ech.enabled"), Field("tls.ech.config", Kind.LIST))
            }
        }
        return result
    }

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
        val v = input.trim()
        if (v.isEmpty()) return null // Omitted, not a forced zero/false.
        return when (field.kind) {
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
                if (field.kind == Kind.PINS) for (pin in parts) {
                    require(pin.matches(Regex("[A-Za-z0-9+/]{43}="))) { "Use padded Base64 SHA-256 SPKI pins" }
                    val bytes = try { Base64.decode(pin, Base64.NO_WRAP) } catch (_: IllegalArgumentException) { null }
                    require(bytes?.size == 32) { "Use Base64 SHA-256 SPKI pins (32 bytes each)" }
                }
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

    fun validate(bean: AbstractBean, root: JsonObject) {
        validateQuicECH(bean, root)
        // Only validate fields owned by this editor; leave arbitrary custom configuration to strict core validation.
        for (field in fields(bean)) get(root, field.path)?.let {
            value(field, text(root, field))
        }
        val min = get(root, "tls.min_version")?.asString
        val max = get(root, "tls.max_version")?.asString
        require(min == null || max == null || min <= max) { "tls.min_version must not exceed tls.max_version" }
        val certificate = get(root, "tls.certificate")
        val nativeCertificate = when (bean) {
            is AnyTLSBean -> bean.certificates
            is StandardV2RayBean -> bean.certificates
            is HysteriaBean -> bean.caText
            is TuicBean -> bean.caText
            else -> ""
        }
        require(get(root, "tls.certificate_public_key_sha256") == null ||
            (certificate == null && get(root, "tls.certificate_path") == null && nativeCertificate.isNullOrBlank())) {
            "TLS public-key pins conflict with custom CA certificates"
        }
        val reality = get(root, "tls.reality.enabled")?.asBoolean == true ||
            (bean is StandardV2RayBean && !bean.realityPubKey.isNullOrBlank())
        val ech = get(root, "tls.ech.enabled")?.asBoolean == true ||
            (bean is StandardV2RayBean && bean.enableECH == true) || (bean is AnyTLSBean && !bean.echConfig.isNullOrBlank())
        require(!(reality && ech)) { "Reality conflicts with ECH in the current core" }
        require(!reality || get(root, "tls.disable_sni")?.asBoolean != true) { "Reality requires SNI" }
        require(!reality || get(root, "tls.certificate_public_key_sha256") == null) { "Reality uses its own public key verification, not certificate pins" }
        require(!reality || max == null || max == "1.3") { "Reality requires TLS 1.3" }
        if (bean is TuicBean && get(root, "udp_over_stream")?.asBoolean == true) {
            val relay = get(root, "udp_relay_mode")?.asString ?: if (bean.udpRelayMode == "quic") "quic" else ""
            require(relay.isEmpty()) { "udp_over_stream conflicts with udp_relay_mode; select native relay first" }
        }
        if (bean is HysteriaBean && bean.protocolVersion == 2) {
            val maxHop = get(root, "hop_interval_max")?.asString
            val minHop = get(root, "hop_interval")?.asString ?: "${bean.hopInterval}s"
            val minNanos = durationNanos(minHop)
            val maxNanos = maxHop?.let { durationNanos(it) } ?: BigDecimal.ZERO
            require(maxNanos.signum() == 0 || minNanos.signum() > 0 && maxNanos >= minNanos) { "hop_interval_max must not be shorter than hop_interval" }
            if (bean.serverPorts.any { it == ',' || it == '-' || it == ':' }) {
                require(minNanos.signum() == 0 || minNanos >= BigDecimal("5000000000")) { "Port-hop interval must be at least 5s" }
            }
            val obfsType = get(root, "obfs.type")?.asString
            if (obfsType != null) require(!bean.obfuscation.isNullOrBlank() || get(root, "obfs.password") != null) { "Obfuscation requires a password" }
            val minPacket = get(root, "obfs.min_packet_size")?.asLong
            val maxPacket = get(root, "obfs.max_packet_size")?.asLong
            val effectiveMin = minPacket?.takeIf { it != 0L } ?: 512L
            val effectiveMax = maxPacket?.takeIf { it != 0L } ?: 1200L
            require(effectiveMin <= effectiveMax) { "Obfuscation packet size range is reversed" }
            require((minPacket == null && maxPacket == null) || obfsType == "gecko") { "Packet size options require gecko obfuscation" }
        }
    }

    fun hasAdvancedOptions(bean: AbstractBean): Boolean =
        !bean.protocolOptionsJson.isNullOrBlank() ||
            try { fields(bean).any { get(parse(bean.customOutboundJson), it.path) != null } } catch (_: Exception) { false }

    fun applyTo(outbound: moe.matsuri.nb4a.SingBoxOptions.SingBoxOption, bean: AbstractBean, external: Boolean = false) {
        if (external) {
            require(bean.protocolOptionsJson.isNullOrBlank()) { "Native protocol advanced options cannot be used with an external plugin; clear protocol advanced options first" }
            return
        }
        val root = parse(bean.protocolOptionsJson)
        validateQuicECH(bean, root)
        // Built-in DTO -> protocol extension -> existing explicit user override (serializer).
        val values: Map<String, Any?> = com.google.gson.GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE).create().fromJson(effectiveOptions(bean, root),
            object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type)
        moe.matsuri.nb4a.utils.Util.mergeMap(outbound._hack_config_map, values)
    }
}
