package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions
import moe.matsuri.nb4a.SingBoxOptions.RuleSet
import moe.matsuri.nb4a.checkEmpty
import moe.matsuri.nb4a.generateRuleSet
import moe.matsuri.nb4a.makeSingBoxRule
import moe.matsuri.nb4a.utils.listByLineOrComma

/** Appends saved route and DNS rules after the destination tags have been assigned. */
internal fun appendUserRoutingRules(
    route: RouteOptions,
    savedRules: List<RuleEntity>,
    primaryProfileId: Long,
    outboundTags: Map<Long, String>,
    isVpn: Boolean,
    userDnsRules: MutableList<DNSRule_DefaultOptions>,
    directDnsStrategy: String?,
    remoteDnsStrategy: String?,
    fakeDnsPolicy: String?,
) {
    for (rule in savedRules) {
        if (rule.packages.isNotEmpty()) {
            PackageCache.awaitLoadSync()
        }
        val uidList = rule.packages.map {
            if (!isVpn) {
                Toast.makeText(
                    SagerNet.application,
                    SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            PackageCache[it]?.takeIf { uid -> uid >= 1000 }
        }.toHashSet().filterNotNull()
        val ruleSets = mutableListOf<RuleSet>()

        val ruleObj = Rule_DefaultOptions().apply {
            if (uidList.isNotEmpty()) {
                PackageCache.awaitLoadSync()
                user_id = uidList
            }
            var domainList: List<String>? = null
            if (rule.domains.isNotBlank()) {
                domainList = rule.domains.listByLineOrComma()
                makeSingBoxRule(domainList, false)
            }
            if (rule.ip.isNotBlank()) {
                makeSingBoxRule(rule.ip.listByLineOrComma(), true)
            }

            if (rule_set != null) generateRuleSet(rule_set, ruleSets)

            if (rule.port.isNotBlank()) {
                port = mutableListOf<Int>()
                port_range = mutableListOf<String>()
                rule.port.listByLineOrComma().map {
                    if (it.contains(":")) {
                        port_range.add(it)
                    } else {
                        it.toIntOrNull()?.apply { port.add(this) }
                    }
                }
            }
            if (rule.sourcePort.isNotBlank()) {
                source_port = mutableListOf<Int>()
                source_port_range = mutableListOf<String>()
                rule.sourcePort.listByLineOrComma().map {
                    if (it.contains(":")) {
                        source_port_range.add(it)
                    } else {
                        it.toIntOrNull()?.apply { source_port.add(this) }
                    }
                }
            }
            if (rule.network.isNotBlank()) {
                network = listOf(rule.network)
            }
            if (rule.source.isNotBlank()) {
                source_ip_cidr = rule.source.listByLineOrComma()
            }
            if (rule.protocol.isNotBlank()) {
                protocol = rule.protocol.listByLineOrComma()
            }

            fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                return DNSRule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) user_id = uidList
                    domainList?.let { makeSingBoxRule(it) }
                }
            }

            when (rule.outbound) {
                -1L -> {
                    userDnsRules += makeDnsRuleObj().apply {
                        routeDnsRule(this, "dns-direct", directDnsStrategy)
                    }
                }

                0L -> {
                    if (fakeDnsPolicy != null) userDnsRules += makeDnsRuleObj().apply {
                        routeDnsRule(this, "dns-fake", fakeDnsPolicy)
                        inbound = listOf("tun-in")
                    }
                    userDnsRules += makeDnsRuleObj().apply {
                        routeDnsRule(this, "dns-remote", remoteDnsStrategy)
                    }
                }

                -2L -> {
                    userDnsRules += makeDnsRuleObj().apply {
                        action = "predefined"
                        rcode = "NOERROR"
                    }
                }
            }

            outbound = when (val outId = rule.outbound) {
                0L -> TAG_PROXY
                -1L -> TAG_BYPASS
                -2L -> TAG_BLOCK
                else -> if (outId == primaryProfileId) TAG_PROXY else outboundTags[outId] ?: ""
            }

            _hack_custom_config = rule.config
        }

        if (!ruleObj.checkEmpty()) {
            if (ruleObj.outbound.isNullOrBlank()) {
                Toast.makeText(
                    SagerNet.application,
                    "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // block 改用新的写法
                if (ruleObj.outbound == TAG_BLOCK) {
                    ruleObj.outbound = null
                    ruleObj.action = "reject"
                }
                route.rules.add(ruleObj)
                route.rule_set.addAll(ruleSets)
            }
        }
    }

    // Keep the first definition for each rule-set tag.
    if (route.rule_set != null) {
        route.rule_set = route.rule_set.distinctBy { it.tag }
    }
}
