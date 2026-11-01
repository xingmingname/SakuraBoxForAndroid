package io.nekohasekai.sagernet.fmt.sudoku

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import android.util.Base64
import io.nekohasekai.sagernet.fmt.NodeJson
import moe.matsuri.nb4a.utils.ImportLimits
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Upstream shortlink.go (56ce6f1) and Sudodroid ShortLinkCodec.kt (b02186d).
 * This is an opaque Base64 JSON payload, not a userinfo/host/query URI.
 * No SakuraBox-only fields are added to the upstream payload. */
object SudokuShareLink {
    const val MAX_URI_LENGTH = 64 * 1024
    private const val PREFIX = "sudoku://"
    private val mask = mapOf("hd" to "disable", "hm" to "mode", "ht" to "tls",
        "hh" to "host", "hx" to "multiplex", "hy" to "path_root")
    private val keys = setOf("n", "h", "p", "k", "a", "e", "m", "x", "t", "ts") + mask.keys
    private val outboundKeys = setOf("key", "ascii", "aead", "custom_table", "custom_tables",
        "padding_min", "padding_max", "enable_pure_downlink", "httpmask")
    private val aead = setOf("", "none", "aes-128-gcm", "chacha20-poly1305")
    private val modes = setOf("", "legacy", "stream", "poll", "auto", "ws")
    private val mux = setOf("", "off", "auto", "on")

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid or unsupported Sudoku share link")
    private fun string(root: JsonObject, key: String): String? = root.get(key)?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) invalid()
        it.asString.also(::checkUnicode)
    }
    private fun integer(root: JsonObject, key: String): Int? = root.get(key)?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) invalid()
        it.asString.toIntOrNull() ?: invalid()
    }
    private fun bool(root: JsonObject, key: String): Boolean? = root.get(key)?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) invalid()
        it.asBoolean
    }
    private fun tables(value: JsonElement?) {
        if (value == null) return
        if (!value.isJsonArray || value.asJsonArray.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) invalid()
        value.asJsonArray.forEach { checkUnicode(it.asString) }
    }
    private fun checkUnicode(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i++]
            if (ch.isHighSurrogate()) {
                if (i == text.length || !text[i++].isLowSurrogate()) invalid()
            } else if (ch.isLowSurrogate()) invalid()
        }
    }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun fragment(text: String): String {
        checkUnicode(text)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            if (text[i] == '%') {
                if (i + 2 >= text.length) invalid()
                val hi = text[i + 1].digitToIntOrNull(16) ?: invalid()
                val lo = text[i + 2].digitToIntOrNull(16) ?: invalid()
                bytes.write(hi * 16 + lo); i += 3
            } else {
                val end = text.indexOf('%', i).takeIf { it >= 0 } ?: text.length
                bytes.write(text.substring(i, end).toByteArray(Charsets.UTF_8)); i = end
            }
        }
        return utf8(bytes.toByteArray()) // '+' is a literal plus, as in Sudodroid.
    }

    fun parse(link: String): SudokuBean {
        ImportLimits.checkCancelled()
        if (link.length > MAX_URI_LENGTH) throw ImportLimits.InputLimitException("Sudoku link exceeds 64 KiB")
        val trimmed = link.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) invalid()
        val body = trimmed.substring(PREFIX.length)
        val encoded = body.substringBefore('#').filterNot { it.isWhitespace() }
        val standard = '+' in encoded || '/' in encoded
        if (!encoded.matches(Regex(if (standard) "[A-Za-z0-9+/]+={0,2}" else "[A-Za-z0-9_-]+={0,2}"))) invalid()
        val dataLength = encoded.indexOf('=').takeIf { it >= 0 } ?: encoded.length
        val padding = encoded.length - dataLength
        if (dataLength % 4 == 1 || padding > 0 &&
            (encoded.length % 4 != 0 || padding == 1 && dataLength % 4 != 3 || padding == 2 && dataLength % 4 != 2)) invalid()
        val flags = Base64.NO_WRAP or if (standard) 0 else Base64.URL_SAFE
        val raw = try { utf8(Base64.decode(encoded, flags)) } catch (_: IllegalArgumentException) { invalid() }
        ImportLimits.checkText(raw)
        val payload = NodeJson.parse(raw)
        // Unknown fields may alter connection semantics in another implementation. Refuse
        // that node instead of silently accepting a partial configuration.
        if (payload.keySet().any { it !in keys }) throw IllegalArgumentException("Unsupported Sudoku share link fields")
        val host = string(payload, "h") ?: invalid()
        val port = integer(payload, "p") ?: invalid()
        val key = string(payload, "k") ?: invalid()
        if (host.isBlank() || host.any { it.isWhitespace() || it.isISOControl() || it in "/?#@\\" } ||
            port !in 1..65535 || key.isEmpty()) invalid()
        val localPort = integer(payload, "m")
        if (localPort != null && localPort !in 0..65535) invalid()
        val ascii = string(payload, "a").orEmpty()
        if (ascii.isNotEmpty() && ascii !in SudokuOptions.asciiModes) invalid()
        val encryption = string(payload, "e").orEmpty()
        if (encryption !in aead) invalid()
        val http = JsonObject()
        for ((wire, field) in mask) {
            if (!payload.has(wire)) continue
            if (wire == "hd" || wire == "ht") http.addProperty(field, bool(payload, wire) ?: invalid())
            else http.addProperty(field, string(payload, wire) ?: invalid())
        }
        if (http.has("mode") && http["mode"].asString !in modes ||
            http.has("multiplex") && http["multiplex"].asString !in mux) invalid()
        val options = JsonObject().apply {
            addProperty("key", key) // Never trim, percent-decode, or case-fold key material.
            addProperty("aead", encryption.ifEmpty { "none" })
            addProperty("ascii", when (ascii) { "", "entropy" -> "prefer_entropy"; "ascii" -> "prefer_ascii"; else -> ascii })
            addProperty("enable_pure_downlink", !(bool(payload, "x") ?: false))
            // Upstream URI defaults differ from this fork's ordinary outbound defaults (10/30).
            addProperty("padding_min", 5); addProperty("padding_max", 15)
            string(payload, "t")?.let { addProperty("custom_table", it) }
            tables(payload["ts"]); payload["ts"]?.let { add("custom_tables", it.deepCopy()) }
            if (http.size() > 0) add("httpmask", http)
        }
        val payloadName = string(payload, "n")
        val fragmentName = if ('#' in body) fragment(body.substringAfter('#')) else ""
        return SudokuBean().apply {
            serverAddress = host; serverPort = port
            name = payloadName?.takeIf { it.isNotBlank() } ?: fragmentName.ifBlank { host }
            protocolOptionsJson = options.toString()
            shareLinkLocalPort = localPort
            initializeDefaultValues()
            NodeJson.checkStorage(this)
        }
    }

    /** Returns null when the upstream format cannot retain this node's configuration.
     * Callers keep the established sn:// path in that case. Never mutate saved options. */
    fun export(bean: SudokuBean): String? = try {
        if (NodeJson.enabled(bean) || !bean.customOutboundJson.isNullOrBlank() || !bean.customConfigJson.isNullOrBlank() ||
            (bean.jsonLegacyMask ?: 0) != 0) invalid()
        checkUnicode(bean.name.orEmpty()); checkUnicode(bean.serverAddress.orEmpty())
        val options = SudokuOptions.root(bean)
        if (options.keySet().any { it !in outboundKeys }) invalid()
        // There is no padding field in the real URI. Even omitted outbound padding is
        // incompatible: this core uses 10/30, whereas short-link clients use 5/15.
        if (integer(options, "padding_min") != 5 || integer(options, "padding_max") != 15) invalid()
        val payload = JsonObject().apply {
            addProperty("h", bean.serverAddress); addProperty("p", bean.serverPort)
            addProperty("k", string(options, "key") ?: invalid())
            if (!bean.name.isNullOrEmpty()) addProperty("n", bean.name)
            addProperty("a", string(options, "ascii").orEmpty().ifEmpty { "prefer_ascii" })
            addProperty("e", string(options, "aead").orEmpty().ifEmpty { "chacha20-poly1305" })
            addProperty("x", !(bool(options, "enable_pure_downlink") ?: true))
            bean.shareLinkLocalPort?.let { addProperty("m", it) }
            string(options, "custom_table")?.let { addProperty("t", it) }
            tables(options["custom_tables"]); options["custom_tables"]?.let { add("ts", it.deepCopy()) }
        }
        options["httpmask"]?.let { value ->
            if (!value.isJsonObject || value.asJsonObject.keySet().any { it !in mask.values }) invalid()
            val http = value.asJsonObject
            for ((wire, field) in mask) {
                if (!http.has(field)) continue
                if (wire == "hd" || wire == "ht") payload.addProperty(wire, bool(http, field) ?: invalid())
                else payload.addProperty(wire, string(http, field) ?: invalid())
            }
        }
        // Android Base64 is available at the app's minimum API 21. java.util.Base64
        // requires API 26 and is not supplied by this project's desugaring library.
        val result = PREFIX + Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        parse(result) // Same input/encoding/shape budgets as import; no core runtime validation.
        result
    } catch (_: IllegalArgumentException) { null } catch (_: com.google.gson.JsonParseException) { null }
      catch (_: java.io.IOException) { null }
}
