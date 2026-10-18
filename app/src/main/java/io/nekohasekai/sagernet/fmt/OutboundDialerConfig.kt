package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption

internal fun configureOutboundResolver(outbound: SingBoxOption, strategy: String, forTest: Boolean) {
    if (outbound is CustomSingBoxOption && outbound.basicMap.containsKey("domain_resolver")) return
    outbound._hack_config_map["domain_resolver"] = mapOf(
        "server" to "dns-direct",
        "strategy" to if (forTest || strategy.isBlank()) "as_is" else strategy,
    )
}

internal fun setChainDetour(outbound: SingBoxOption, tag: String) {
    outbound._hack_config_map["detour"] = tag
    // Preserve the previous chain behavior: later dialers receive the domain.
    // Explicit user resolver options in custom JSON still take precedence.
    outbound._hack_config_map.remove("domain_resolver")
}
