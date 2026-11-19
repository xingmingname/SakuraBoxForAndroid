package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions

/** Used by the production generator, before node/global overrides are merged. */
internal fun buildDnsServers(directAddress: String, remoteAddress: String?, proxyTag: String): List<DNSServerOptions> {
    fun server(address: String, tag: String, resolver: String, detourTag: String? = null): DNSServerOptions {
        val endpoint = parseDnsServerAddress(address)
        return DNSServerOptions().apply {
            type = endpoint.type
            this.tag = tag
            server = endpoint.server
            server_port = endpoint.serverPort
            path = endpoint.path
            interfaceName = endpoint.interfaceName
            // Local/DHCP use Android's underlying network. A remote transport's
            // default dialer is direct in 1.14, so proxy DNS needs an explicit detour.
            if (endpoint.server != null) {
                domain_resolver = resolver
                detour = detourTag
            }
        }
    }
    return buildList {
        add(DNSServerOptions().apply { type = "local"; tag = "dns-local" })
        add(server(directAddress, "dns-direct", "dns-local"))
        if (remoteAddress != null) add(server(remoteAddress, "dns-remote", "dns-direct", proxyTag))
    }
}
