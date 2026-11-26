package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.IPv6Mode
import moe.matsuri.nb4a.SingBoxOptions.DNSOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.makeSingBoxRule

/** Installs built-in routing and DNS rules after the saved rules have been appended. */
internal fun applyBuiltInRoutingRules(
    route: RouteOptions,
    dns: DNSOptions,
    forTest: Boolean,
    useFakeDns: Boolean,
    ipv6Mode: Int,
    directDomains: List<String>,
    directDnsStrategy: String?,
    bypassLan: () -> Boolean,
) {
    if (forTest) {
        dns.rules = listOf()
    } else {
        // built-in DNS rules
        route.rules.add(0, Rule_DefaultOptions().apply {
            protocol = listOf("dns")
            action = "hijack-dns"
        })
        route.rules.add(0, Rule_DefaultOptions().apply {
            port = listOf(53)
            action = "hijack-dns"
        })
        if (bypassLan()) {
            route.rules.add(Rule_DefaultOptions().apply {
                outbound = TAG_BYPASS
                ip_is_private = true
            })
        }
        // block mcast
        route.rules.add(Rule_DefaultOptions().apply {
            ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
            source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
            action = "reject"
        })
        // FakeDNS obj
        if (useFakeDns) {
            dns.servers.add(DNSServerOptions().apply {
                type = "fakeip"
                tag = "dns-fake"
                inet4_range = "198.18.0.0/15"
                inet6_range = "fc00::/18"
            })
            dns.rules.add(DNSRule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                routeDnsRule(this, "dns-fake", fakeDnsStrategy(ipv6Mode == IPv6Mode.ONLY))
                disable_cache = true
            })
        }
        // force bypass (always top DNS rule)
        if (directDomains.isNotEmpty()) {
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                makeSingBoxRule(directDomains.toHashSet().toList())
                routeDnsRule(this, "dns-direct", directDnsStrategy)
            })
        }
    }
}
