package io.nekohasekai.sagernet.fmt.nakis

import android.util.Base64
import com.google.common.net.InetAddresses
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import moe.matsuri.nb4a.utils.ImportLimits
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/** Import of the Nakis v1 link.go format at 33eb35178ad6c6774144baf562f589d612578603. */
object NakisShareLink {
    const val PREFIX = "nakis://"
    private val uuidHex = Regex("[0-9a-fA-F]{32}")
    private val pinHex = Regex("[0-9a-f]{64}")
    private val base64 = Regex("[A-Za-z0-9+/]*")
    private val durationTerm = Regex("""([0-9]+(?:\.[0-9]*)?|\.[0-9]+)(ns|us|µs|μs|ms|s|m|h)""")
    private val durationUnits = mapOf(
        "ns" to 1L, "us" to 1000L, "µs" to 1000L, "μs" to 1000L, "ms" to 1_000_000L,
        "s" to 1_000_000_000L, "m" to 60_000_000_000L, "h" to 3_600_000_000_000L,
    )
    private val maxDuration = BigDecimal.valueOf(Long.MAX_VALUE)

    private val uriNumbers = mapOf(
        "race_count" to "race", "pool_max_uses" to "uses", "send_pack" to "pack",
        "send_tail" to "tail", "send_quiet" to "quiet",
    )
    private val uriDurations = mapOf(
        "pool_max_age" to ("age" to "15s"), "keepalive" to ("ka" to "15s"),
        "shape_wait" to ("wait" to "20ms"),
    )

    fun export(bean: NakisBean): String? {
        if (NodeJson.enabled(bean) || bean.customConfigJson.orEmpty().isNotBlank() ||
            bean.customOutboundJson.orEmpty().isNotBlank()) return null
        return runCatching { formatV1(bean) }.getOrNull()
    }

    private fun formatV1(bean: NakisBean): String {
        val options = ProtocolOptions.parse(bean.protocolOptionsJson)
        // Keep SN available whenever v1 cannot represent the saved node.
        val supported = setOf("uuid", "tls", "sni", "tls_pins", "ech_config", "server_alternates",
            "shape_up", "pool_lo", "pool_hi", "congestion") + uriNumbers.keys + uriDurations.keys
        require(options.keySet().all { it in supported })
        require(NakisOptions.effectiveOptions(options).keySet() == options.keySet())

        fun string(key: String) = options[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        }

        fun number(key: String) = options[key]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
            requireNotNull(it.asString.toIntOrNull())
        }

        fun strings(key: String) = options.getAsJsonArray(key)?.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
            it.asString
        }

        val hex = requireNotNull(string("uuid")).replace("-", "").lowercase(Locale.ROOT)
        require(uuidHex.matches(hex))
        val uuid = listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16),
            hex.substring(16, 20), hex.substring(20)).joinToString("-")
        val (host, port) = normalizeEndpoint(bean.serverAddress.removeSurrounding("[", "]"), bean.serverPort.toString())
        val authority = if (':' in host) "[${host.replace("%", "%25")}]:$port" else "$host:$port"
        val params = linkedMapOf("v" to "1")
        val tls = options["tls"]?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean)
            it.asBoolean
        } ?: true
        if (!tls) params["tls"] = "0"
        string("sni")?.let { params["sni"] = normalizeHost(it) }
        strings("tls_pins")?.takeIf { it.isNotEmpty() }?.let { params["pin"] = it.joinToString(",") }
        string("ech_config")?.let {
            params["ech"] = Base64.encodeToString(Base64.decode(normalizeEch(it), Base64.DEFAULT),
                Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
        }
        strings("server_alternates")?.takeIf { it.isNotEmpty() }?.let { params["alt"] = it.joinToString(",") }
        options.getAsJsonArray("shape_up")?.takeIf { it.size() > 0 }?.let { rows ->
            params["shape"] = rows.joinToString(",") {
                val range = it.asJsonArray
                require(range.size() == 2 && range.all { value -> value.isJsonPrimitive && value.asJsonPrimitive.isNumber })
                "${range[0].asString}-${range[1].asString}"
            }
        }
        for ((key, param) in uriNumbers) number(key)?.let { params[param] = it.toString() }
        val lo = number("pool_lo")
        val hi = number("pool_hi")
        if (lo != null || hi != null) params["pool"] = "${lo ?: 2}-${hi ?: 32}"
        for ((key, field) in uriDurations) {
            string(key)?.let { params[field.first] = duration(it, field.second) }
        }
        string("congestion")?.takeIf { it.isNotEmpty() }?.let { params["cc"] = it }

        val link = buildString {
            append(PREFIX).append(uuid).append('@').append(authority).append('?')
            append(params.entries.joinToString("&") { "${it.key}=${encode(it.value)}" })
            if (!bean.name.isNullOrEmpty()) append('#').append(encode(bean.name))
        }
        val roundTrip = ProtocolOptions.parse(parseV1(link).protocolOptionsJson)
        // Both the URI and core normalize these numbers with the same official rules.
        require(roundTrip["uuid"]?.asString == uuid)
        return link
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun parse(link: String): NakisBean = try {
        parseV1(link)
    } catch (_: IllegalArgumentException) {
        // The Java URI and escape decoders can include credentials in exceptions.
        throw IllegalArgumentException("Invalid or unsupported Nakis share link")
    } catch (_: java.net.URISyntaxException) {
        throw IllegalArgumentException("Invalid or unsupported Nakis share link")
    }

    private fun parseV1(link: String): NakisBean {
        ImportLimits.checkText(link)
        val input = link.trimLinkSpace()
        require(input.startsWith(PREFIX, ignoreCase = true))
        val main = input.substring(PREFIX.length).substringBefore('#')
        val address = main.substringBefore('?')
        val authority = address.substringBefore('/')
        // Validate the authority's escapes and bracketed IPv6 with the JVM URI parser.
        require(URI(PREFIX + authority).rawAuthority == authority)
        require('@' in authority)
        val uuid = decode(authority.substringBeforeLast('@').substringBefore(':'), form = false)
        require(uuidHex.matches(uuid.replace("-", "")))
        val (rawHost, rawPort) = endpoint(authority.substringAfterLast('@'))
        val (host, port) = normalizeEndpoint(decode(rawHost, form = false), rawPort)
        // Go's URL parser also checks percent escapes in the otherwise unused path.
        decode(address.substringAfter('/', ""), form = false)

        val query = mutableMapOf<String, String>()
        for (item in main.substringAfter('?', "").splitToSequence('&')) {
            if (item.isEmpty()) continue
            require(';' !in item)
            val key = decode(item.substringBefore('='), form = true)
            val value = decode(item.substringAfter('=', ""), form = true)
            if (key !in query) query[key] = value // url.Values.Get uses the first value.
        }
        fun value(key: String) = query[key]?.takeIf { it.isNotEmpty() }
        fun number(key: String) = value(key)?.let(::integer)
        number("v")?.let { require(it == 1L) }

        val tls = query["tls"] != "0"
        val sni = value("sni")?.let(::normalizeHost)
        val pin = value("pin")
        val ech = value("ech")
        require(tls || (pin == null && ech == null))
        require(!tls || sni != null || pin != null)
        require(ech == null || sni != null)

        val options = JsonObject().apply {
            addProperty("uuid", uuid)
            if ("tls" in query) addProperty("tls", tls)
            sni?.let { addProperty("sni", it) }
            pin?.let { pins ->
                add("tls_pins", JsonArray().apply {
                    pins.splitToSequence(',').forEach {
                        val normalized = it.trim().replace(":", "").lowercase(Locale.ROOT)
                        require(pinHex.matches(normalized))
                        add(normalized)
                    }
                })
            }
            ech?.let { addProperty("ech_config", normalizeEch(it)) }
            value("alt")?.let { alternates ->
                val servers = linkedSetOf(host to port)
                for (entry in alternates.splitToSequence(',').map { it.trimLinkSpace() }.filter { it.isNotEmpty() }) {
                    val (address, service) = endpoint(entry)
                    servers.add(normalizeEndpoint(address, service))
                }
                if (servers.size > 1) {
                    add("server_alternates", JsonArray().apply {
                        for ((address, service) in servers.drop(1)) {
                            add(if (':' in address) "[$address]:$service" else "$address:$service")
                        }
                    })
                }
            }
            value("shape")?.let { shape ->
                add("shape_up", JsonArray().apply {
                    for (entry in shape.splitToSequence(',')) {
                        require(size() < 8)
                        val (low, high) = range(entry)
                        require(low in 1..16382 && high in low..16382)
                        add(JsonArray().apply { add(low); add(high) })
                    }
                })
            }
            number("race")?.let { addProperty("race_count", it.coerceIn(2, 12)) }
            value("pool")?.let {
                val (low, high) = range(it)
                val floor = low.coerceIn(1, 1024)
                addProperty("pool_lo", floor)
                addProperty("pool_hi", high.coerceIn(floor + 1, 4096))
            }
            number("uses")?.let { addProperty("pool_max_uses", it.coerceIn(0, 100)) }
            value("age")?.let { addProperty("pool_max_age", duration(it, "15s")) }
            value("ka")?.let { addProperty("keepalive", duration(it, "15s")) }
            value("cc")?.let { addProperty("congestion", it) }
            val pack = number("pack")?.coerceIn(1024, 65533)
            pack?.let { addProperty("send_pack", it) }
            number("tail")?.let { addProperty("send_tail", it.coerceIn(0, (pack ?: 20480) - 1)) }
            number("quiet")?.let { addProperty("send_quiet", it.coerceIn(0, 64)) }
            value("wait")?.let { addProperty("shape_wait", duration(it, "20ms")) }
        }
        return NakisBean().apply {
            serverAddress = host
            serverPort = port
            name = decode(input.substringAfter('#', ""), form = false)
            protocolOptionsJson = options.toString()
            initializeDefaultValues()
            NodeJson.checkStorage(this)
        }
    }

    private fun decode(text: String, form: Boolean) =
        URLDecoder.decode(if (form) text else text.replace("+", "%2B"), "UTF-8")

    // SplitHostPort-compatible splitting; the endpoint is normalized before storage.
    private fun endpoint(text: String): Pair<String, String> {
        val separator = text.lastIndexOf(':')
        require(separator >= 0)
        val rawHost = text.substring(0, separator)
        val host = if (rawHost.startsWith('[')) {
            require(rawHost.endsWith(']'))
            rawHost.substring(1, rawHost.length - 1)
        } else {
            require(':' !in rawHost)
            rawHost
        }
        val port = text.substring(separator + 1)
        require('[' !in host && ']' !in host && '[' !in port && ']' !in port)
        return host to port
    }

    private fun normalizeEndpoint(host: String, port: String): Pair<String, Int> {
        require(port.all { it in '0'..'9' })
        val service = requireNotNull(port.trimStart('0').toIntOrNull())
        require(service in 1..65535)
        if ('%' !in host) return normalizeHost(host) to service

        val literal = host.substringBefore('%')
        val zone = host.substringAfter('%')
        require(':' in literal && zone.isNotEmpty())
        return requireNotNull(canonicalIp(literal)) + "%" + zone to service
    }

    private fun canonicalIp(host: String): String? {
        // Go netip accepts ASCII literals only. Guava also accepts Unicode hex digits.
        if (host.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' && it != ':' && it != '.' }) return null
        if (!InetAddresses.isInetAddress(host)) return null
        val address = InetAddresses.toAddrString(InetAddresses.forString(host))
        // The JVM exposes an IPv4-mapped IPv6 address as Inet4Address.
        return if (':' in host && ':' !in address) "::ffff:$address" else address
    }

    private fun String.trimLinkSpace() = trim { it in '\t'..'\r' || it == '\u0085' || Character.isSpaceChar(it) }

    // Mirrors the upstream normHost rules without resolving names.
    private fun normalizeHost(input: String): String {
        val host = input.trimLinkSpace()
        require(host.isNotEmpty() && '%' !in host)
        canonicalIp(host)?.let { return it }
        val name = host.removeSuffix(".")
        require(name.isNotEmpty() && name.toByteArray(Charsets.UTF_8).size <= 253 &&
            !name.startsWith('.') && ".." !in name)
        require(name.none { it <= ' ' || it == '\u007f' || it in "/\\?#@,[]:\"'<>" })
        return buildString(name.length) {
            var offset = 0
            while (offset < name.length) {
                val codePoint = name.codePointAt(offset)
                appendCodePoint(Character.toLowerCase(codePoint))
                offset += Character.charCount(codePoint)
            }
        }
    }

    private fun range(text: String): Pair<Long, Long> {
        val trimmed = text.trim()
        require('-' in trimmed)
        return integer(trimmed.substringBefore('-')) to integer(trimmed.substringAfter('-'))
    }

    private fun integer(text: String): Long {
        val digits = if (text.startsWith('+') || text.startsWith('-')) text.substring(1) else text
        require(digits.isNotEmpty() && digits.all { it in '0'..'9' })
        return requireNotNull(text.toLongOrNull())
    }

    private fun normalizeEch(text: String): String {
        val normalized = text.trim().trimEnd('=').replace(' ', '+').replace('-', '+').replace('_', '/')
            .replace("\r", "").replace("\n", "")
        require(base64.matches(normalized) && normalized.length % 4 != 1)
        val bytes = Base64.decode(normalized, Base64.DEFAULT)
        require(bytes.size >= 4)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // Keep positive values in Go duration syntax. Tidy replaces nonpositive values.
    private fun duration(text: String, fallback: String): String =
        if (durationNanos(text).signum() <= 0) fallback else text

    internal fun durationNanos(text: String): BigDecimal {
        val negative = text.startsWith('-')
        val unsigned = if (negative || text.startsWith('+')) text.substring(1) else text
        if (unsigned == "0") return BigDecimal.ZERO
        var offset = 0
        var nanos = BigDecimal.ZERO
        for (match in durationTerm.findAll(unsigned)) {
            require(match.range.first == offset)
            val unit = BigDecimal.valueOf(durationUnits.getValue(match.groupValues[2]))
            nanos += (match.groupValues[1].toBigDecimal() * unit).setScale(0, RoundingMode.DOWN)
            offset = match.range.last + 1
        }
        require(offset != 0 && offset == unsigned.length)
        val limit = if (negative) maxDuration + BigDecimal.ONE else maxDuration
        require(nanos <= limit)
        return if (negative) nanos.negate() else nanos
    }
}

