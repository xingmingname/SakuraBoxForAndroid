package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject

object MtlsOptions {
    val certificate = ProtocolOptions.Field("tls.client_certificate", ProtocolOptions.Kind.PEM)
    val key = ProtocolOptions.Field("tls.client_key", ProtocolOptions.Kind.PEM)

    fun supported(bean: AbstractBean) = ProtocolOptions.protocol(bean) in setOf("anytls", "vless", "vmess", "trojan", "http")
    fun isSet(text: String?) = !text.isNullOrBlank()

    fun omitBlankInline(tls: JsonObject) {
        for (name in listOf("client_certificate", "client_key")) {
            val value = tls.get(name) ?: continue
            val blank = when {
                value.isJsonNull -> true
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.isBlank()
                value.isJsonArray -> value.asJsonArray.all {
                    it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isBlank()
                }
                else -> false
            }
            if (blank) tls.remove(name)
        }
    }
}
