package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test

class VpnTunPlanTest {
    @Test fun actualCoreJSONKeepsFamilyPrefixMTUAndDNS() {
        val v4 = VpnTunPlan.parse("""{"MTU":1400,"Inet4Address":["172.19.0.1/28"],"Inet6Address":null,"DNSAddress":null}""")
        assertEquals(1400, v4.mtu)
        assertEquals(listOf("172.19.0.1/28"), v4.addresses)
        assertEquals(listOf("172.19.0.2"), v4.dnsServers())
        assertFalse(v4.hasIpv6)
        val v6 = VpnTunPlan.parse("""{"MTU":1500,"Inet4Address":null,"Inet6Address":["fdfe:dcba:9876::1/126"]}""")
        assertFalse(v6.hasIpv4)
        assertTrue(v6.hasIpv6)
        assertEquals(listOf("fdfe:dcba:9876:0:0:0:0:2"), v6.dnsServers())
        val custom = VpnTunPlan.parse("""{"MTU":1300,"Inet4Address":["10.0.0.1/24"],"DNSAddress":["10.0.0.53"]}""")
        assertEquals(listOf("10.0.0.53"), custom.dnsServers())
    }
    @Test fun noAddressOrZeroMTUIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { VpnTunPlan.parse("""{"MTU":1400}""") }
        assertThrows(IllegalArgumentException::class.java) { VpnTunPlan.parse("""{"MTU":0,"Inet4Address":["10.0.0.1/24"]}""") }
    }
}
