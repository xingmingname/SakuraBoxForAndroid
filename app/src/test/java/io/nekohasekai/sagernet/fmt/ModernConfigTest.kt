package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModernConfigTest {
    private fun fixture(name: String, map: Map<String, Any?>) {
        System.getenv("NEKO_CONFIG_FIXTURES")?.let {
            File(it).mkdirs(); File(it, "modern-$name.json").writeText(Gson().toJson(map))
        }
    }

    @Test fun familyPoliciesAndModernUserOverridesReachFinalConfig() {
        for (policy in listOf("ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6", "as_is")) {
            val proxy = buildSingBoxOutboundSocksBean(SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 1080
            }).apply { tag = "proxy"; configureOutboundResolver(this, "prefer_ipv4", false) }
            val root = MyOptions().apply {
                dns = DNSOptions().apply {
                    servers = buildDnsServers("udp://127.0.0.1:15353", "udp://127.0.0.1:15353", "proxy")
                    rules = listOf(DNSRule_DefaultOptions().apply {
                        domain = listOf("family.example.invalid")
                        routeDnsRule(this, "dns-direct", policy)
                    })
                    final_ = "dns-direct"; strategy = dnsLookupPreference(policy)
                }
                outbounds = listOf(proxy, Outbound().apply { type="direct"; tag="direct" })
                route = RouteOptions().apply { final_="proxy" }
            }
            val map = root.asMap()
            Util.mergeJSON(map, """{"dns":{"rules+":[{"query_type":["TXT"],"action":"predefined","rcode":"NOERROR"},{"ip_version":6,"domain":["override.example.invalid"],"action":"predefined","rcode":"NOERROR"}]}}""")
            finalizeGeneratedConfig(map)
            val text = Gson().toJson(map)
            assertFalse(text.contains("_neko_dns_policy"))
            val dns = map["dns"] as Map<*, *>
            val rules = dns["rules"] as List<*>
            for (rule in rules) { assertFalse((rule as Map<*, *>).containsKey("strategy")); assertFalse(rule.containsKey("outbound")) }
            assertEquals(if(policy.endsWith("_only")) 4 else 3, rules.size)
            assertTrue(text.contains("query_type")); assertTrue(text.contains("ip_version"))
            fixture("family-$policy", map)
        }
    }

    @Test fun wireguardEndpointDefaultAndExplicitOverrideArePreserved() {
        val endpoint = buildSingBoxEndpointWireguardBean(WireGuardBean().apply {
            initializeDefaultValues(); serverAddress="127.0.0.1"; serverPort=51820
            localAddress="10.0.0.2/32"; privateKey="BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
            peerPublicKey="CAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAg="
        }).apply { tag="proxy"; configureOutboundResolver(this, "prefer_ipv4", false) }
        val map = MyOptions().apply {
            endpoints=listOf(endpoint)
            outbounds=listOf(Outbound().apply { type="direct";tag="direct" }, Outbound().apply { type="direct";tag="bypass" })
            route=RouteOptions().apply { final_="proxy" }
        }.asMap()
        fixture("wireguard-default", map)
        Util.mergeJSON(map, """{"route":{"final":"bypass"}}""")
        finalizeGeneratedConfig(map)
        assertEquals("bypass", (map["route"] as Map<*, *>)["final"])
        fixture("wireguard-override", map)
    }

    @Test fun fakeipFamilyPolicyIsSharedAcrossUserAndDefaultRules() {
        for (only6 in listOf(false,true)) {
            val policy=fakeDnsStrategy(only6)
            val root=MyOptions().apply {
                outbounds=listOf(Outbound().apply { type="direct";tag="direct" })
                dns=DNSOptions().apply {
                servers=buildDnsServers("udp://127.0.0.1:1",null,"direct") +
                    DNSServerOptions().apply { type="fakeip";tag="dns-fake";inet4_range="198.18.0.0/15";inet6_range="fc00::/18" }
                final_="dns-direct"
                rules=listOf(DNSRule_DefaultOptions().apply { domain=listOf("test.invalid");routeDnsRule(this,"dns-fake",policy) },
                    DNSRule_DefaultOptions().apply { user_id=listOf(10001);routeDnsRule(this,"dns-fake",policy) },
                    DNSRule_DefaultOptions().apply { inbound=listOf("tun-in");routeDnsRule(this,"dns-fake",policy) })
            }}.asMap()
            finalizeGeneratedConfig(root)
            val text=Gson().toJson(root)
            assertEquals(3, Regex(if(only6) "\\\"A\\\"" else "\\\"AAAA\\\"").findAll(text).count())
            assertFalse(text.contains("strategy"))
            fixture("fakeip-$only6",root)
        }
    }

    @Test fun hysteria1UsesSeparateModernQUICWindows() {
        val bean=io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean().apply {
            protocolVersion=1;initializeDefaultValues();serverAddress="127.0.0.1";serverPorts="443"
            authPayload="synthetic-test";authPayloadType=io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean.TYPE_STRING
            sni="example.invalid";streamReceiveWindow=1048576;connectionReceiveWindow=2097152;disableMtuDiscovery=true
        }
        val node=io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean(bean,false)
        node._hack_config_map["tag"]="proxy"
        val map=node.asMap()
        assertEquals(1048576L,(map["stream_receive_window"] as Number).toLong())
        assertEquals(2097152L,(map["connection_receive_window"] as Number).toLong())
        assertEquals(true,map["disable_path_mtu_discovery"])
        assertFalse(map.containsKey("recv_window_conn"));assertFalse(map.containsKey("recv_window"));assertFalse(map.containsKey("disable_mtu_discovery"))
        fixture("hysteria1",MyOptions().apply {outbounds=listOf(node);route=RouteOptions().apply {final_="proxy"}}.asMap())
    }

    @Test fun finalFamilyGuardComesAfterUserOverridesAndKeepsTheirPrecedence() {
        val root=MyOptions().apply { dns=DNSOptions().apply {
            rules=emptyList();_hack_config_map["_neko_final_dns_policy"]="ipv6_only"
        }}.asMap()
        Util.mergeJSON(root,"""{"dns":{"rules+":[{"query_type":["TXT"],"action":"predefined","rcode":"NXDOMAIN"}]}}""")
        finalizeGeneratedConfig(root)
        val rules=(root["dns"] as Map<*,*>)["rules"] as List<*>
        assertEquals("NXDOMAIN",(rules[0] as Map<*,*>)["rcode"])
        assertEquals(listOf("A"),(rules[1] as Map<*,*>)["query_type"])
        assertFalse(Gson().toJson(root).contains("_neko_"))
    }

    @Test fun inboundResolutionRetainsPerRulePreferencesBeforeFallback() {
        val root=MyOptions().apply {
            inbounds=listOf(Inbound_MixedOptions().apply {type="mixed";tag="in";listen="127.0.0.1";domain_strategy="prefer_ipv4"})
            outbounds=listOf(Outbound().apply {type="direct";tag="direct"})
            route=RouteOptions().apply {final_="direct";rules=emptyList()}
            dns=DNSOptions().apply {
                servers=buildDnsServers("udp://127.0.0.1:15353",null,"direct")
                final_="dns-direct";strategy="prefer_ipv4"
                rules=listOf(DNSRule_DefaultOptions().apply {domain=listOf("family.example.invalid");routeDnsRule(this,"dns-direct","prefer_ipv6")})
            }
        }.asMap()
        finalizeGeneratedConfig(root)
        assertFalse(((root["inbounds"] as List<*>)[0] as Map<*,*>).containsKey("domain_strategy"))
        val rules=(root["route"] as Map<*,*>)["rules"] as List<*>
        assertEquals("prefer_ipv6",(rules[0] as Map<*,*>)["strategy"])
        assertEquals("prefer_ipv4",(rules[1] as Map<*,*>)["strategy"])
        assertFalse(Gson().toJson(root["dns"]).contains("_neko_"))
        fixture("inbound-preferences",root)
    }
}
