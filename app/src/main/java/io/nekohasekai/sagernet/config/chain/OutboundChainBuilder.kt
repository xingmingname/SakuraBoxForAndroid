package io.nekohasekai.sagernet.config.chain

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.DashboardRoute
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.TAG_DIRECT
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.fmt.buildNativeProfileOptions
import io.nekohasekai.sagernet.fmt.configureOutboundResolver
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.setChainDetour
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.Inbound_DirectOptions
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_SocksOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.WireGuardEndpointOptions
import moe.matsuri.nb4a.plugin.Plugins

/** Assembles the outbounds, plugin mapping and profile metadata for one config build. */
internal class OutboundChainBuilder(
    private val config: MyOptions,
    private val primaryProfile: ProxyEntity,
    private val buildSelector: Boolean,
    private val forTest: Boolean,
    private val collectDashboardRoutes: Boolean,
    private val directDnsDomains: MutableList<String>,
    private val resolveProfiles: (ProxyEntity) -> List<ProxyEntity>,
    private val serverDomainStrategy: () -> String,
) {
    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val externalIndex = ArrayList<IndexEntity>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val dashboardRoutes = mutableMapOf<String, DashboardRoute>()
    val jsonOutbounds = mutableMapOf<String, SingBoxOption>()

    private val globalOutbounds = HashMap<Long, String>()
    private val selectorNames = ArrayList<String>()

    private fun selectorName(name_: String): String {
        var name = name_
        var count = 0
        while (selectorNames.contains(name)) {
            count++
            name = "$name_-$count"
        }
        selectorNames.add(name)
        return name
    }

    fun build(chainId: Long, entity: ProxyEntity): String {
        val profileList = resolveProfiles(entity)
        val chainTrafficSet = HashSet<ProxyEntity>().apply {
            plusAssign(profileList)
            add(entity)
        }

        var currentOutbound: SingBoxOption
        lateinit var pastOutbound: SingBoxOption
        lateinit var pastInboundTag: String
        var pastEntity: ProxyEntity? = null
        val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
        externalIndex.add(IndexEntity(externalChainMap))

        // Tag referenced by the selector or routing rules for this chain.
        var chainTagOut = ""
        val chainTag = "c-$chainId"
        var muxApplied = false

        val defaultServerDomainStrategy = serverDomainStrategy()

        profileList.forEachIndexed { index, proxyEntity ->
            val jsonOutbound = proxyEntity.requireBean().jsonMode == NodeJson.OUTBOUND
            val bean = if (jsonOutbound) proxyEntity.requireBean().clone() else proxyEntity.requireBean()
            if (jsonOutbound) {
                val raw = NodeJson.parse(bean.customOutboundJson)
                NodeJson.string(raw, "server")?.let { bean.serverAddress = it }
                raw.get("server_port")?.let { bean.serverPort = it.asInt }
            }

            // Outbound tag for this hop.
            // profile2 (in) (global)   tag g-(id)
            // profile1                 tag (chainTag)-(id)
            // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
            var tagOut = "$chainTag-${proxyEntity.id}"

            // needGlobal: can only contain one?
            var needGlobal = false

            // first profile set as global
            if (index == profileList.lastIndex) {
                needGlobal = true
                tagOut = "g-" + proxyEntity.id
                bypassDNSBeans += bean
            }

            // last profile set as "proxy"
            if (chainId == 0L && index == 0) {
                tagOut = TAG_PROXY
            }

            // selector human readable name
            if (buildSelector && index == 0) {
                tagOut = selectorName(bean.displayName())
            }


            // chain rules
            if (index > 0) {
                // chain route/proxy rules
                if (pastEntity!!.needExternal()) {
                    config.route.rules.add(Rule_DefaultOptions().apply {
                        inbound = listOf(pastInboundTag)
                        outbound = tagOut
                    })
                } else {
                    setChainDetour(pastOutbound, tagOut)
                }
            } else {
                // index == 0 means last profile in chain / not chain
                chainTagOut = tagOut
            }

            // now tagOut is determined
            if (needGlobal) {
                globalOutbounds[proxyEntity.id]?.let {
                    if (index == 0) chainTagOut = it // single, duplicate chain
                    return@forEachIndexed
                }
                globalOutbounds[proxyEntity.id] = tagOut
            }

            if (jsonOutbound) {
                currentOutbound = CustomSingBoxOption(NodeJson.outbound(bean.customOutboundJson, tagOut).toString())
                jsonOutbounds[tagOut] = currentOutbound
            } else if (proxyEntity.needExternal()) {
                val localPort = mkPort()
                externalChainMap[localPort] = proxyEntity
                currentOutbound = Outbound_SocksOptions().apply {
                    type = "socks"
                    server = LOCALHOST
                    server_port = localPort
                }
            } else {
                // internal outbound

                currentOutbound = buildNativeProfileOptions(bean, tagOut)

                // internal mux
                if (!muxApplied) {
                    val muxObj = proxyEntity.singMux()
                    if (muxObj != null && muxObj.enabled) {
                        muxApplied = true
                        currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                    }
                }
            }

            // internal & external
            currentOutbound.apply {
                if (!jsonOutbound) {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            directDnsDomains.add("full:$serverAddress")
                        }
                    }
                    configureOutboundResolver(this, defaultServerDomainStrategy, forTest)

                    ProtocolOptions.applyTo(this, bean, proxyEntity.needExternal())
                    _hack_custom_config = NodeJson.legacyOutbound(bean)
                }
                _hack_config_map["tag"] = tagOut
            }

            // External proxy need a dokodemo-door inbound to forward the traffic
            // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
            bean.finalAddress = bean.serverAddress
            bean.finalPort = bean.serverPort
            if (bean.canMapping() && proxyEntity.needExternal()) {
                // With ss protect, don't use mapping
                var needExternal = true
                if (index == profileList.lastIndex) {
                    val pluginId = when (bean) {
                        is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                        else -> ""
                    }
                    if (Plugins.isUsingMatsuriExe(pluginId)) {
                        needExternal = false
                    } else if (Plugins.getPluginExternal(pluginId) != null) {
                        throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                    }
                }
                if (needExternal) {
                    val mappingPort = mkPort()
                    bean.finalAddress = LOCALHOST
                    bean.finalPort = mappingPort

                    config.inbounds.add(Inbound_DirectOptions().apply {
                        type = "direct"
                        listen = LOCALHOST
                        listen_port = mappingPort
                        tag = "$chainTag-mapping-${proxyEntity.id}"

                        override_address = bean.serverAddress
                        override_port = bean.serverPort

                        pastInboundTag = tag

                        // no chain rule and not outbound, so need to set to direct
                        if (index == profileList.lastIndex) {
                            config.route.rules.add(Rule_DefaultOptions().apply {
                                inbound = listOf(tag)
                                outbound = TAG_DIRECT
                            })
                        }
                    })
                }
            }

            if (currentOutbound is WireGuardEndpointOptions || jsonOutbound && NodeJson.string(NodeJson.parse(bean.customOutboundJson), "type") == "wireguard") {
                config.endpoints.add(currentOutbound)
            } else {
                config.outbounds.add(currentOutbound)
            }
            pastOutbound = currentOutbound
            pastEntity = proxyEntity
        }

        trafficMap[chainTagOut] = chainTrafficSet.toList()
        if (collectDashboardRoutes) {
            val knownPath = NodeJson.legacyFull(primaryProfile.requireBean()).isBlank() && profileList.all {
                val bean = it.requireBean()
                bean.jsonMode != NodeJson.OUTBOUND && NodeJson.legacyOutbound(bean).isBlank()
            }
            val hops = if (knownPath) profileList.asReversed().take(32).map {
                "${it.displayName().take(120)} · ${runCatching { it.displayType() }.getOrDefault("")}"
            } else emptyList()
            dashboardRoutes[chainTagOut] = DashboardRoute(
                entity.displayName().orEmpty().take(120), runCatching { entity.displayType() }.getOrDefault(""), hops,
                if (knownPath) profileList.size else 0,
            )
        }
        return chainTagOut
    }
}
