package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonParser
import moe.matsuri.nb4a.utils.ClashApiSecretStore

/** Applied after overrides, including complete custom JSON. Export/test never
 * alters the active main service credential. Explicit non-empty secrets remain. */
internal fun secureClashApiConfig(
    config: String,
    activate: ((String, String) -> Unit)? = null,
): String {
    val parsed = JsonParser.parseString(config)
    require(parsed.isJsonObject) { "Configuration must be an object" }
    val root = parsed.asJsonObject
    val experimentalValue = root.get("experimental")?.takeUnless { it.isJsonNull }
    require(experimentalValue == null || experimentalValue.isJsonObject) { "experimental must be an object" }
    val apiValue = experimentalValue?.asJsonObject?.get("clash_api")?.takeUnless { it.isJsonNull }
    require(apiValue == null || apiValue.isJsonObject) { "experimental.clash_api must be an object" }
    val api = apiValue?.asJsonObject
    val controllerValue = api?.get("external_controller")?.takeUnless { it.isJsonNull }
    require(controllerValue == null || (controllerValue.isJsonPrimitive && controllerValue.asJsonPrimitive.isString)) {
        "Clash API controller must be a string"
    }
    val controller = controllerValue?.asString.orEmpty()
    if (controller.isEmpty()) {
        activate?.invoke("", "")
        return config
    }
    val existing = api!!.get("secret")
    require(existing == null || existing.isJsonNull || (existing.isJsonPrimitive && existing.asJsonPrimitive.isString)) {
        "Clash API secret must be a string"
    }
    val secret = existing?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotEmpty() }
        ?: ClashApiSecretStore.newSecret()
    api.addProperty("secret", secret)
    activate?.invoke(controller, secret)
    return Gson().toJson(root)
}
