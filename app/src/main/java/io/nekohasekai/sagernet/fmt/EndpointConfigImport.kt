package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import moe.matsuri.nb4a.proxy.config.ConfigBean

/** Endpoints belong to a complete config and must retain route/DNS references. */
internal fun importEndpointConfig(text: String): ConfigBean? {
    val json = JsonParser.parseString(text)
    if (!json.isJsonObject) return null
    val endpoints = json.asJsonObject.get("endpoints") ?: return null
    require(endpoints.isJsonArray) { "sing-box endpoints must be an array" }
    if (endpoints.asJsonArray.size() == 0) return null
    return ConfigBean().apply {
        initializeDefaultValues()
        type = 0
        config = text
        name = "sing-box endpoints"
    }
}
