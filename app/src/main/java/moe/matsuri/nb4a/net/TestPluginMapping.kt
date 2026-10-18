package moe.matsuri.nb4a.net

import com.google.gson.JsonParser
import java.io.IOException

/** External-process sockets cannot be bound by a Go instance's controller. */
internal fun requireTestPluginMappings(config: String, endpoints: List<Pair<String, Int>>) {
    if (endpoints.isEmpty()) return
    val root = JsonParser.parseString(config).asJsonObject
    val ports = root.getAsJsonArray("inbounds")?.mapNotNull { value ->
        val inbound = value.asJsonObject
        if (inbound.get("type")?.asString == "direct" &&
            inbound.get("listen")?.asString == "127.0.0.1" &&
            inbound.has("override_address") && inbound.has("override_port")
        ) inbound.get("listen_port")?.asInt else null
    }?.toSet() ?: emptySet()
    if (endpoints.any { (address, port) -> address != "127.0.0.1" || port !in ports }) {
        throw IOException("Independent URL Test cannot bind external plugin sockets that bypass the core loopback mapping")
    }
}
