package io.nekohasekai.sagernet.fmt

import libcore.Libcore
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.config.chain.OutboundChainBuilder
import io.nekohasekai.sagernet.config.inbound.buildTunInbound
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val jsonSources: Map<Long, NodeJson.State> = emptyMap(),
    val dashboardRoutes: Map<String, DashboardRoute> = emptyMap(),
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
    data class DashboardRoute(val name: String, val protocol: String, val hops: List<String>, val hopCount: Int)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false, forPreview: Boolean = false
): ConfigBuildResult {

    fun completeConfig(text: String, legacy: Boolean = false): com.google.gson.JsonObject {
        val root = NodeJson.parse(Libcore.migrateConfig(text))
        if (!forTest && !forExport && !forPreview)
            requireAndroidJsonVpn(root, DataStore.serviceMode == Key.MODE_VPN, legacy)
        return root
    }

    val sourceBean = proxy.requireBean()
    NodeJson.state(sourceBean)
    if (sourceBean.jsonMode == NodeJson.FULL) {
        val root = completeConfig(sourceBean.customConfigJson)
        NodeJson.validateReferences(root)
        val effective = if (forTest) NodeJson.testConfig(root) else root
        val target = NodeJson.runtimeTag(root)
        return ConfigBuildResult(
            Libcore.migrateConfig(secureClashApiConfig(effective.toString(),
                if (!forTest && !forExport && !forPreview) DataStore::activateClashApi else null)),
            emptyList(), proxy.id, mapOf(target to listOf(proxy)), mapOf(proxy.id to target), -1L,
            mapOf(proxy.id to NodeJson.state(sourceBean)))
    }

    if (proxy.type == TYPE_CONFIG && sourceBean.jsonMode != NodeJson.OUTBOUND) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val root = completeConfig(bean.config, legacy = true)
            return ConfigBuildResult(
                Libcore.migrateConfig(secureClashApiConfig(root.toString(),
                    if (!forTest && !forExport && !forPreview) DataStore::activateClashApi else null)),
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L
            )
        }
    }

    val tagMap = HashMap<Long, String>()
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    val chainProfiles = ChainProfileResolver()

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val needSniffOverride = DataStore.trafficSniffing == 2
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    val config = MyOptions()
    val chainBuilder = OutboundChainBuilder(
        config = config,
        primaryProfile = proxy,
        buildSelector = buildSelector,
        forTest = forTest,
        collectDashboardRoutes = !forTest && !forExport && !forPreview,
        directDnsDomains = domainListDNSDirectForce,
        resolveProfiles = chainProfiles::resolve,
        serverDomainStrategy = { SingBoxOptionsUtil.domainStrategy("server") },
    )

    return config.apply {
        if (!forTest && DataStore.enableClashAPI) experimental = ExperimentalOptions().apply {
            clash_api = ClashAPIOptions().apply {
                external_controller = "127.0.0.1:9090"
                external_ui = "../files/yacd"
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
        }

        endpoints = mutableListOf()

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        val directDnsStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-direct"))
        val remoteDnsStrategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("dns-remote"))

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(buildTunInbound(
                implementation = DataStore.tunImplementation,
                mtuValue = DataStore.mtu,
                domainStrategy = genDomainStrategy(DataStore.resolveDestination),
                sniffTraffic = needSniff,
                overrideDestination = needSniffOverride,
                ipv6Mode = ipv6Mode,
            ))
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                domain_strategy = genDomainStrategy(DataStore.resolveDestination)
                sniff = needSniff
                sniff_override_destination = needSniffOverride
            })
        }

        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            rules = mutableListOf()
            rule_set = mutableListOf()
            _hack_config_map["default_domain_resolver"] = mapOf(
                "server" to "dns-direct",
                "strategy" to if (forTest) "as_is" else (directDnsStrategy ?: "as_is"),
            )
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }.map { if (it.id == proxy.id) proxy else it }.toMutableList()
            if (list.none { it.id == proxy.id }) list.add(proxy)
            list.filter { entry ->
                val bean = entry.requireBean()
                when {
                    bean.jsonMode == NodeJson.FULL -> false
                    entry.id == proxy.id || bean.jsonMode != NodeJson.OUTBOUND -> true
                    else -> try {
                        val out = NodeJson.outbound(bean.customOutboundJson, TAG_PROXY)
                        val standalone = com.google.gson.JsonObject().apply {
                            add(if (NodeJson.string(out, "type") == "wireguard") "endpoints" else "outbounds",
                                com.google.gson.JsonArray().apply { add(out) })
                        }
                        NodeJsonProfile.validateCore(standalone)
                        true
                    } catch (_: Exception) { false } // A broken inactive JSON node must not stop the selected node.
                }
            }.forEach {
                tagMap[it.id] = chainBuilder.build(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                route.final_ = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            route.final_ = chainBuilder.build(0, proxy)
            tagMap[proxy.id] = route.final_
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = chainBuilder.build(key, p)
        }

        appendUserRoutingRules(
            route, extraRules, proxy.id, tagMap, isVPN, userDNSRuleList,
            directDnsStrategy, remoteDnsStrategy, if (useFakeDns) fakeDnsStrategy(ipv6Mode == IPv6Mode.ONLY) else null,
        )

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // Bypass Lookup for the first profile
        chainBuilder.bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach {
            val host = runCatching { parseDnsServerAddress(it).server }.getOrNull()
            if (host != null && !host.isIpAddress()) {
                domainListDNSDirectForce.add("full:$host")
            }
        }

        dns.servers.addAll(buildDnsServers(
            directDNS.firstOrNull() ?: error("No direct DNS, check your settings!"),
            if (forTest) null else remoteDns.firstOrNull() ?: error("No remote DNS, check your settings!"),
            TAG_PROXY,
        ))

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"
        // Lookup preferences remain explicit; only-mode filtering is expanded into
        // modern query-type guards after all user overrides have been merged.
        dns.strategy = dnsLookupPreference(if (forTest) directDnsStrategy else remoteDnsStrategy)
        dns._hack_config_map["_neko_final_dns_policy"] = if (forTest) directDnsStrategy else remoteDnsStrategy

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        applyBuiltInRoutingRules(
            route, dns, forTest, useFakeDns, ipv6Mode, domainListDNSDirectForce, directDnsStrategy,
            bypassLan = { DataStore.bypassLanInCore },
        )

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, NodeJson.legacyFull(proxy.requireBean()))
        NodeJson.restoreOutbounds(configMap, chainBuilder.jsonOutbounds.mapValues { it.value.asMap() })
        finalizeGeneratedConfig(configMap)
        ConfigBuildResult(
            Libcore.migrateConfig(secureClashApiConfig(gson.toJson(configMap),
                if (!forTest && !forExport && !forPreview) DataStore::activateClashApi else null)),
            chainBuilder.externalIndex,
            proxy.id,
            chainBuilder.trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            chainBuilder.trafficMap.values.flatten().associate { it.id to NodeJson.state(it.requireBean()) },
            chainBuilder.dashboardRoutes
        )
    }

}
