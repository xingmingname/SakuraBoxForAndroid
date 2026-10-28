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
    @Test fun jsonRoutesHonorExclusionsOnOlderAndroidWithoutTouchingOrdinaryPlans() {
        val plan = VpnTunPlan.parse("""{"MTU":1400,"AutoRoute":true,"Inet4Address":["10.0.0.1/24"],"Inet4RouteAddress":["10.0.0.0/24"],"Inet4RouteExcludeAddress":["10.0.0.128/25"]}""")
        assertEquals(listOf("10.0.0.0" to 25), plan.jsonRoutes())
        plan.autoRoute = false
        assertTrue(plan.jsonRoutes().isEmpty())
        assertEquals(listOf("10.0.0.2"), plan.dnsServers())
    }
    @Test fun ipv6RoutesAndFamilyDefaultsRemainDistinct() {
        val plan = VpnTunPlan.parse("""{"MTU":1400,"AutoRoute":true,"Inet6Address":["fd00::1/126"],"Inet6RouteAddress":["fd00::/126"],"Inet6RouteExcludeAddress":["fd00::2/127"]}""")
        assertEquals(listOf("fd00:0:0:0:0:0:0:0" to 127), plan.jsonRoutes())
        plan.routes6 = null; plan.exclude6 = null
        assertEquals(listOf("0:0:0:0:0:0:0:0" to 0), plan.jsonRoutes())
    }
    @Test fun exclusionsCanRemoveTheWholeFamilyAndCannotAcceptDnsNames() {
        val plan = VpnTunPlan.parse("""{"MTU":1400,"AutoRoute":true,"Inet4Address":["10.0.0.1/24"],"Inet4RouteExcludeAddress":["0.0.0.0/0"]}""")
        assertTrue(plan.jsonRoutes().isEmpty())
        plan.routes4 = listOf("example.invalid/24")
        assertThrows(IllegalArgumentException::class.java) { plan.jsonRoutes() }
    }
}
