package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class NodeJsonProtectionTest {
    private fun config(route: String = "{}", out: String = "", auto: Boolean = true) = NodeJson.parse(
        """{"inbounds":[{"type":"tun","address":["172.19.0.1/30"],"auto_route":$auto}],"route":$route,"outbounds":[{"type":"direct"$out}]}""")
    private fun rejected(route: String = "{}", out: String = "") {
        assertEquals(NodeJson.ErrorKind.ENVIRONMENT, assertThrows(NodeJson.Invalid::class.java) {
            NodeJson.requireVpn(config(route, out), true, setOf("wlan0"))
        }.kind)
    }
    @Test fun unprotectedAutoRouteIsRejectedWithoutMutatingUserJson() {
        val root = config(); val before = root.deepCopy()
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireVpn(root, true) }
        assertEquals(before, root)
        rejected("""{"auto_detect_interface":false}""")
    }
    @Test fun automaticProtectionAndExplicitPhysicalBindingAreAccepted() {
        NodeJson.requireVpn(config("""{"auto_detect_interface":true}"""), true)
        NodeJson.requireVpn(config("""{"default_interface":"wlan0"}"""), true, setOf("wlan0"))
        NodeJson.requireVpn(config(out = """, "bind_interface":"wlan0""""), true, setOf("wlan0"))
        rejected(out = """, "bind_interface":"tun0"""")
        rejected("""{"auto_detect_interface":true,"default_interface":"tun0"}""")
    }
    @Test fun sourceAddressOverridesCannotSilentlyDisableAutomaticProtection() {
        rejected("""{"auto_detect_interface":true}""", """, "inet4_bind_address":"192.0.2.1"""")
        NodeJson.requireVpn(config("""{"auto_detect_interface":true}""",
            """, "inet4_bind_address":"192.0.2.1", "bind_interface":"wlan0""""), true, setOf("wlan0"))
    }
    @Test fun genericProtectPathCannotSubstituteForTheCheckedPlatformCallback() {
        rejected(out = """, "protect_path":"protect_path"""")
        rejected("""{"auto_detect_interface":true}""",
            """, "inet4_bind_address":"192.0.2.1", "protect_path":"protect_path"""")
        NodeJson.requireVpn(config("""{"auto_detect_interface":true}""", """, "protect_path":"protect_path""""), true)
    }
    @Test fun autoRouteFalseAndProxyServiceKeepTheirOriginalSemantics() {
        NodeJson.requireVpn(config(auto = false), true)
        val proxy = NodeJson.parse("""{"outbounds":[{"type":"direct"}]}""")
        NodeJson.requireVpn(proxy, false)
    }
    @Test fun everyUpstreamIncludingDnsMustHaveProtection() {
        val root = config(out = """, "bind_interface":"wlan0"""")
        root.add("dns", NodeJson.parse("""{"servers":[{"type":"udp","tag":"dns","server":"192.0.2.2"}]}"""))
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireVpn(root, true, setOf("wlan0")) }
        root.getAsJsonObject("dns").getAsJsonArray("servers")[0].asJsonObject.addProperty("bind_interface", "wlan0")
        NodeJson.requireVpn(root, true, setOf("wlan0"))
    }
    @Test fun inheritedTransportDoesNotRequireASecondBindingAndLegacyDnsIsChecked() {
        val root = config(out = """, "bind_interface":"wlan0", "transport":{"type":"quic"}""")
        NodeJson.requireVpn(root, true, setOf("wlan0"))
        root.add("dns", NodeJson.parse("""{"servers":[{"tag":"old","address":"udp://192.0.2.2"}]}"""))
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireVpn(root, true, setOf("wlan0")) }
    }

    @Test fun legacyTunUsesAndroidRoutesAndRequiresProtectionRegardlessOfAutoRoute() {
        for (auto in listOf(false, true)) {
            val root = config(auto = auto); val before = root.deepCopy()
            assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireLegacyVpnProtection(root, true) }
            assertEquals(before, root)
            NodeJson.requireLegacyVpnProtection(config("""{"auto_detect_interface":true}""", auto = auto), true)
            NodeJson.requireLegacyVpnProtection(config("""{"default_interface":"wlan0"}""", auto = auto), true, setOf("wlan0"))
            NodeJson.requireLegacyVpnProtection(config(out = """, "bind_interface":"wlan0"""", auto = auto), true, setOf("wlan0"))
        }
    }
    @Test fun legacyProxyOnlyAndNonVpnConfigurationsAreUnaffected() {
        NodeJson.requireLegacyVpnProtection(NodeJson.parse("""{"outbounds":[{"type":"direct"}]}"""), true)
        NodeJson.requireLegacyVpnProtection(NodeJson.parse("""{"outbounds":[{"type":"direct"}]}"""), false)
        NodeJson.requireLegacyVpnProtection(config(), false)
        NodeJson.requireVpn(config(auto = false), true)
    }
    @Test fun legacyProtectionRejectsStaleBindingAndUnprotectedDns() {
        val root = config("""{"auto_detect_interface":true}""", """, "inet4_bind_address":"192.0.2.1"""")
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireLegacyVpnProtection(root, true) }
        val bound = config(out = """, "bind_interface":"tun0"""")
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireLegacyVpnProtection(bound, true, setOf("wlan0")) }
        val dns = config(out = """, "bind_interface":"wlan0"""")
        dns.add("dns", NodeJson.parse("""{"servers":[{"type":"udp","server":"192.0.2.2"}]}"""))
        assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireLegacyVpnProtection(dns, true, setOf("wlan0")) }
    }
}
