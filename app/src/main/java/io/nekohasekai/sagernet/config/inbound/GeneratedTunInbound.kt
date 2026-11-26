package io.nekohasekai.sagernet.config.inbound

import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.TunImplementation
import io.nekohasekai.sagernet.bg.VpnService
import moe.matsuri.nb4a.SingBoxOptions.Inbound_TunOptions

internal fun buildTunInbound(
    implementation: Int,
    mtuValue: Int,
    domainStrategy: String,
    sniffTraffic: Boolean,
    overrideDestination: Boolean,
    ipv6Mode: Int,
): Inbound_TunOptions = Inbound_TunOptions().apply {
    type = "tun"
    tag = "tun-in"
    stack = when (implementation) {
        TunImplementation.GVISOR -> "gvisor"
        TunImplementation.SYSTEM -> "system"
        else -> "mixed"
    }
    _hack_config_map["udp_mapping"] = "endpoint_independent"
    _hack_config_map["udp_filtering"] = "endpoint_independent"
    mtu = mtuValue
    address = mutableListOf()
    domain_strategy = domainStrategy
    sniff = sniffTraffic
    sniff_override_destination = overrideDestination
    when (ipv6Mode) {
        IPv6Mode.DISABLE -> {
            address.add(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
        }

        IPv6Mode.ONLY -> {
            address.add(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
        }

        else -> {
            address.add(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
            address.add(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
        }
    }
}
