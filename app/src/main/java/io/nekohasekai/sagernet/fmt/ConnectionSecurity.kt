package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

/** UI modes map to the existing TLS DTO and native tls.reality.enabled field. */
object ConnectionSecurity {
    enum class Mode(val value: String) { NONE("none"), TLS("tls"), REALITY("reality") }

    fun supported(bean: AbstractBean): List<Mode> = when (bean) {
        is StandardV2RayBean -> Mode.entries
        is AnyTLSBean -> listOf(Mode.TLS, Mode.REALITY)
        else -> emptyList()
    }

    fun mode(bean: AbstractBean, options: JsonObject = displayOptions(bean)): Mode {
        if (bean is StandardV2RayBean && bean.security !in listOf("tls", "reality")) return Mode.NONE
        val enabled = ProtocolOptions.get(options, "tls.reality.enabled")
        if (enabled?.isJsonPrimitive == true && enabled.asJsonPrimitive.isBoolean)
            return if (enabled.asBoolean) Mode.REALITY else Mode.TLS
        return if (bean is StandardV2RayBean &&
            (bean.security == "reality" || !bean.realityPubKey.isNullOrBlank())) Mode.REALITY else Mode.TLS
    }

    private fun displayOptions(bean: AbstractBean): JsonObject =
        try { ProtocolOptions.parse(bean.protocolOptionsJson) } catch (_: Exception) { JsonObject() }

    fun select(bean: AbstractBean, mode: Mode) {
        require(mode in supported(bean)) { "Unsupported connection security mode" }
        var root = ProtocolOptions.parse(bean.protocolOptionsJson)
        val reality = ProtocolOptions.get(root, "tls.reality")
        val hasSavedKey = bean is StandardV2RayBean && !bean.realityPubKey.isNullOrBlank()
        if (mode == Mode.REALITY || reality != null || hasSavedKey) {
            root = ProtocolOptions.set(root,
                ProtocolOptions.Field("tls.reality.enabled", ProtocolOptions.Kind.BOOLEAN),
                (mode == Mode.REALITY).toString())
        }
        if (bean is StandardV2RayBean) bean.security = if (mode == Mode.NONE) "none" else "tls"
        bean.protocolOptionsJson = if (root.size() == 0) "" else root.toString()
    }

    fun publicKey(bean: AbstractBean, options: JsonObject): String =
        ProtocolOptions.get(options, "tls.reality.public_key")?.asString
            ?: (bean as? StandardV2RayBean)?.realityPubKey.orEmpty()

    fun shortId(bean: AbstractBean, options: JsonObject): String =
        ProtocolOptions.get(options, "tls.reality.short_id")?.asString
            ?: (bean as? StandardV2RayBean)?.realityShortId.orEmpty()

    /** Suppress inactive settings in generated configuration; keep the stored options unchanged. */
    fun effectiveOptions(bean: AbstractBean, options: JsonObject): JsonObject {
        if (supported(bean).isEmpty()) return options.deepCopy()
        val result = options.deepCopy()
        val mode = mode(bean, options)
        if (mode == Mode.NONE) {
            result.remove("tls")
            return result
        }
        val tls = result.getAsJsonObject("tls") ?: JsonObject().also { result.add("tls", it) }
        tls.addProperty("enabled", true)
        if (mode == Mode.TLS) {
            tls.remove("reality")
        } else {
            listOf("insecure", "certificate", "certificate_path", "certificate_public_key_sha256",
                "disable_sni", "ech").forEach(tls::remove)
            val reality = tls.getAsJsonObject("reality") ?: JsonObject().also { tls.add("reality", it) }
            reality.addProperty("enabled", true)
            reality.addProperty("public_key", publicKey(bean, options))
            reality.addProperty("short_id", shortId(bean, options))
            val utls = tls.getAsJsonObject("utls") ?: JsonObject().also { tls.add("utls", it) }
            utls.addProperty("enabled", true)
        }
        if (tls.size() == 0) result.remove("tls")
        return result
    }

    fun showsAdvancedField(bean: AbstractBean, options: JsonObject, path: String): Boolean {
        if (supported(bean).isEmpty() || !path.startsWith("tls.")) return true
        return when (mode(bean, options)) {
            Mode.NONE -> false
            Mode.TLS -> true
            Mode.REALITY -> path in setOf("tls.handshake_timeout", "tls.min_version", "tls.max_version", "tls.curve_preferences")
        }
    }

    fun clearAdvancedOptions(bean: AbstractBean, options: JsonObject): JsonObject {
        if (supported(bean).isEmpty()) return JsonObject()
        val result = JsonObject()
        for (key in listOf("enabled", "public_key", "short_id")) {
            val value = ProtocolOptions.get(options, "tls.reality.$key") ?: continue
            val tls = result.getAsJsonObject("tls") ?: JsonObject().also { result.add("tls", it) }
            val reality = tls.getAsJsonObject("reality") ?: JsonObject().also { tls.add("reality", it) }
            reality.add(key, value.deepCopy())
        }
        return result
    }

    fun hasAdditionalOptions(bean: AbstractBean): Boolean {
        if (supported(bean).isEmpty()) return !bean.protocolOptionsJson.isNullOrBlank()
        val root = try { ProtocolOptions.parse(bean.protocolOptionsJson) } catch (_: Exception) { return true }
        val custom = try { ProtocolOptions.parse(NodeJson.legacyOutbound(bean)) } catch (_: Exception) { return true }
        if (custom.has("tls")) return true
        val mode = mode(bean, root)
        if (mode == Mode.NONE) root.remove("tls")
        else {
            val tls = root.get("tls")
            if (tls?.isJsonObject == true) {
                val values = tls.asJsonObject
                values.remove("enabled")
                if (mode == Mode.TLS) values.remove("reality")
                else if (bean is StandardV2RayBean) {
                    val reality = values.get("reality")
                    if (reality?.isJsonObject == true) {
                        reality.asJsonObject.remove("enabled")
                        if (reality.asJsonObject.size() == 0) values.remove("reality")
                    }
                }
                if (values.size() == 0) root.remove("tls")
            }
        }
        return root.size() > 0
    }
}
