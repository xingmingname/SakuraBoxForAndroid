package io.nekohasekai.sagernet.fmt

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Locale

/** A DNS endpoint translated from the address syntax accepted by NekoBox's DNS preferences. */
internal data class DnsServerAddress(
    val type: String,
    val server: String? = null,
    val serverPort: Int? = null,
    val path: String? = null,
    val interfaceName: String? = null,
)

/**
 * Translate the legacy address syntax used by the direct/remote DNS preferences into the
 * explicit DNS server types required by sing-box 1.14.0 and later.
 */
internal fun parseDnsServerAddress(value: String): DnsServerAddress {
    val address = value.trim()
    require(address.isNotEmpty()) { "DNS server address is empty" }
    if (address == "local") return DnsServerAddress("local")
    require(address != "fakeip") {
        "FakeIP is managed by the SakuraBox FakeDNS option, not a DNS preference server"
    }

    val separator = address.indexOf("://")
    val explicitScheme = separator >= 0
    val scheme = if (explicitScheme) {
        address.substring(0, separator).lowercase(Locale.ROOT)
    } else {
        "udp"
    }
    val rawEndpoint = if (explicitScheme) address.substring(separator + 3) else address

    if (scheme == "dhcp") {
        val dhcpInterface = rawEndpoint.trimEnd('/')
        require(
            dhcpInterface.isNotBlank() && '/' !in dhcpInterface && '?' !in dhcpInterface &&
                '#' !in dhcpInterface && '@' !in dhcpInterface,
        ) {
            "DHCP DNS address must be dhcp://auto or dhcp://<interface>"
        }
        val interfaceName = dhcpInterface.takeUnless { it == "auto" }
        return DnsServerAddress("dhcp", interfaceName = interfaceName)
    }

    require(scheme in setOf("udp", "tcp", "tls", "https", "quic", "h3")) {
        when (scheme) {
            "fakeip" -> "FakeIP is managed by the SakuraBox FakeDNS option, not a DNS preference server"
            "rcode" -> "rcode servers were removed; use a DNS predefined rule instead"
            else -> "unsupported DNS server scheme '$scheme'"
        }
    }

    val authorityAndPath = if (!explicitScheme && address.count { it == ':' } > 1 && !address.startsWith("[")) {
        "[$address]"
    } else {
        rawEndpoint
    }
    val uri = try {
        URI("https://$authorityAndPath")
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid DNS server address '$address'", e)
    }
    require(uri.rawUserInfo == null) { "DNS server address must not contain credentials" }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "DNS server address query and fragment components are not supported"
    }

    val endpointUrl = "https://$authorityAndPath".toHttpUrlOrNull()
        ?: throw IllegalArgumentException("Invalid DNS server address '$address'")
    val endpointPath = uri.rawPath.orEmpty()
    if (scheme != "https" && scheme != "h3") {
        require(endpointPath.isEmpty() || endpointPath == "/") {
            "DNS server scheme '$scheme' does not support a URL path"
        }
    }
    val port = uri.port.takeIf { it >= 0 }
    require(port == null || port in 1..65535) { "DNS server port must be between 1 and 65535" }

    return DnsServerAddress(
        type = scheme,
        server = endpointUrl.host,
        serverPort = port,
        path = endpointPath.takeIf { it.isNotEmpty() && it != "/" },
    )
}
