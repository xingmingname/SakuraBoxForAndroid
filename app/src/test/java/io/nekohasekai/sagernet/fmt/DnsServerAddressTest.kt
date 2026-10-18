package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DnsServerAddressTest {
    @Test
    fun parsesHttpsPreferenceAndKeepsPathAndPort() {
        assertEquals(
            DnsServerAddress("https", "dns.example", 8443, "/custom-dns"),
            parseDnsServerAddress("https://dns.example:8443/custom-dns"),
        )
    }

    @Test
    fun mapsLegacyTransportSchemesToExplicitTypes() {
        val cases = mapOf(
            "1.1.1.1" to DnsServerAddress("udp", "1.1.1.1"),
            "udp://1.1.1.1:5353" to DnsServerAddress("udp", "1.1.1.1", 5353),
            "tcp://dns.example:5353" to DnsServerAddress("tcp", "dns.example", 5353),
            "tls://dns.example" to DnsServerAddress("tls", "dns.example"),
            "quic://dns.example" to DnsServerAddress("quic", "dns.example"),
            "h3://dns.example/dns-query" to DnsServerAddress("h3", "dns.example", path = "/dns-query"),
            "local" to DnsServerAddress("local"),
            "dhcp://auto" to DnsServerAddress("dhcp"),
            "dhcp://wlan0" to DnsServerAddress("dhcp", interfaceName = "wlan0"),
        )

        cases.forEach { (input, expected) ->
            assertEquals(input, expected, parseDnsServerAddress(input))
        }
    }

    @Test
    fun supportsIpv6Literals() {
        assertEquals(
            DnsServerAddress("udp", "2001:db8::53"),
            parseDnsServerAddress("2001:db8::53"),
        )
        assertEquals(
            DnsServerAddress("tls", "2001:db8::53", 853),
            parseDnsServerAddress("tls://[2001:db8::53]:853"),
        )
    }

    @Test
    fun rejectsLegacySpecialServersAndLossyUrls() {
        listOf(
            "rcode://success",
            "fakeip",
            "http://dns.example/dns-query",
            "https://user:password@dns.example/dns-query",
            "https://dns.example/dns-query?name=value",
            "udp://dns.example/dns-query",
            "udp://dns.example:70000",
            "dhcp://",
            "dhcp://wlan0?interface=other",
        ).forEach { input ->
            assertThrows(IllegalArgumentException::class.java) {
                parseDnsServerAddress(input)
            }
        }
    }
}
