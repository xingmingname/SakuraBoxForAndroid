package moe.matsuri.nb4a.proxy.anytls

import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun buildSingBoxOutboundAnyTLSBean(bean: AnyTLSBean): SingBoxOptions.Outbound_AnyTLSOptions {
    val realityMode = ConnectionSecurity.mode(bean) == ConnectionSecurity.Mode.REALITY
    return SingBoxOptions.Outbound_AnyTLSOptions().apply {
        type = "anytls"
        server = bean.serverAddress
        server_port = bean.serverPort
        password = bean.password

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.takeIf { !it.isNullOrBlank() }
            if (!realityMode && bean.allowInsecure) insecure = true
            alpn = bean.alpn.takeIf { !it.isNullOrBlank() }?.listByLineOrComma()
            bean.certificates.takeIf { !realityMode && !it.isNullOrBlank() }?.let {
                certificate = it
            }
            (bean.utlsFingerprint.takeIf { !it.isNullOrBlank() } ?: if (realityMode) "chrome" else null)?.let {
                utls = SingBoxOptions.OutboundUTLSOptions().apply {
                    enabled = true
                    fingerprint = it
                }
            }
            bean.echConfig.takeIf { !realityMode && !it.isNullOrBlank() }?.let {
                // In new version, some complex options will be deprecated, so we just do this.
                ech = SingBoxOptions.OutboundECHOptions().apply {
                    enabled = true
                    config = it.lines().filter { line -> line.isNotBlank() }
                }
            }
        }
    }
}

/** Convert only representable AnyTLS outbounds; retain every non-native field in the existing override. */
fun importAnyTLSOutbound(json: String): AnyTLSBean? {
    val root = io.nekohasekai.sagernet.fmt.ProtocolOptions.parse(json)
    if (root.get("type")?.isJsonPrimitive != true || root.getAsJsonPrimitive("type").isString != true || root.get("type").asString != "anytls") return null
    val tlsValue = root.get("tls") ?: return null
    if (!tlsValue.isJsonObject) return null
    val tls = tlsValue.asJsonObject
    if (tls.get("enabled")?.isJsonPrimitive != true || !tls.getAsJsonPrimitive("enabled").isBoolean || tls.get("enabled").asBoolean != true) return null
    fun stringsValid(source: com.google.gson.JsonObject, keys: List<String>): Boolean = keys.all { key ->
        val v = source.get(key)
        v == null || (v.isJsonPrimitive && v.asJsonPrimitive.isString)
    }
    if (!stringsValid(root, listOf("server", "password", "tag")) || !stringsValid(tls, listOf("server_name"))) return null
    if (tls.has("insecure") && (!tls["insecure"].isJsonPrimitive || !tls["insecure"].asJsonPrimitive.isBoolean)) return null
    for (key in listOf("alpn", "certificate")) {
        val v = tls.get(key) ?: continue
        val values = if (v.isJsonArray) v.asJsonArray.toList() else listOf(v)
        if (values.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) return null
        if (key == "alpn" && values.any { it.asString.any { c -> c == ',' || c == '\n' || c == '\r' } }) return null
    }
    val portValue = root.get("server_port") ?: return null
    if (!portValue.isJsonPrimitive || !portValue.asJsonPrimitive.isNumber) return null
    val port = portValue.asString.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val bean = AnyTLSBean().apply { initializeDefaultValues() }
    fun string(key: String, source: com.google.gson.JsonObject = root): String = source.get(key)?.asString ?: ""
    fun lines(key: String): String {
        val value = tls.get(key) ?: return ""
        return if (value.isJsonArray) value.asJsonArray.joinToString("\n") { it.asString } else value.asString
    }
    bean.serverAddress = string("server")
    if (bean.serverAddress.isBlank()) return null
    bean.serverPort = port
    bean.password = string("password")
    bean.name = string("tag")
    bean.sni = string("server_name", tls)
    bean.allowInsecure = tls.get("insecure")?.asBoolean ?: false
    bean.alpn = lines("alpn")
    bean.certificates = lines("certificate")
    val remaining = root.deepCopy()
    listOf("type", "tag", "server", "server_port", "password").forEach { remaining.remove(it) }
    val tlsRemaining = remaining.getAsJsonObject("tls")
    listOf("enabled", "server_name", "insecure", "alpn", "certificate").forEach { tlsRemaining.remove(it) }
    for (key in listOf("utls", "ech")) {
        val source = tls.get(key)
        if (source?.isJsonObject != true || source.asJsonObject.get("enabled")?.asBoolean != true) continue
        val obj = source.asJsonObject
        if (!obj["enabled"].isJsonPrimitive || !obj["enabled"].asJsonPrimitive.isBoolean) return null
        val rest = tlsRemaining.getAsJsonObject(key)
        if (key == "utls") {
            if (!stringsValid(obj, listOf("fingerprint"))) return null
            bean.utlsFingerprint = obj.get("fingerprint")?.asString ?: "chrome"
            rest.remove("fingerprint")
        } else {
            val config = obj.get("config") ?: continue // Keep dynamic ECH/DNS behavior as an explicit override.
            val configs = if (config.isJsonArray) config.asJsonArray.toList() else listOf(config)
            if (configs.any { !it.isJsonPrimitive || !it.asJsonPrimitive.isString }) return null
            bean.echConfig = if (config.isJsonArray) config.asJsonArray.joinToString("\n") { it.asString } else config.asString
            if (bean.echConfig.isBlank()) continue // Empty config also means dynamic ECH; do not disable it.
            rest.remove("config")
        }
        rest.remove("enabled")
        if (rest.size() == 0) tlsRemaining.remove(key)
    }
    if (tlsRemaining.size() == 0) remaining.remove("tls")
    bean.protocolOptionsJson = if (remaining.size() == 0) "" else remaining.toString()
    return bean
}

fun AnyTLSBean.toUri(): String {
    val builder = linkBuilder()
        .host(serverAddress)
        .port(serverPort)
        .username(password)
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }
    if (allowInsecure) {
        builder.addQueryParameter("insecure", "1")
    }
    if (!sni.isNullOrBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (!utlsFingerprint.isNullOrBlank()) {
        builder.addQueryParameter("fp", utlsFingerprint)
    }
    return builder.toLink("anytls")
}

fun parseAnytls(url: String): AnyTLSBean {
    // https://github.com/anytls/anytls-go/blob/main/docs/uri_scheme.md
    val link = url.replace("anytls://", "https://").toHttpUrlOrNull() ?: error(
        "invalid anytls link $url"
    )
    return AnyTLSBean().apply {
        serverAddress = link.host
        serverPort = link.port
        name = link.fragment
        password = link.username
        sni = link.queryParameter("sni") ?: ""
        link.queryParameter("insecure")?.also {
            allowInsecure = it == "1" || it == "true"
        }
        link.queryParameter("fp")?.let {
            utlsFingerprint = it
        }
    }
}
