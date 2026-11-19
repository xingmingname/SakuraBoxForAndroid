package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions

// Transient generator policy. Consumed after every JSON override, before JNI/export.
private const val DNS_POLICY = "_neko_dns_policy"

internal fun routeDnsRule(rule: DNSRule_DefaultOptions, server: String, strategy: String?) {
    rule.server = server
    rule._hack_config_map[DNS_POLICY] = strategy ?: "as_is"
}

internal fun fakeDnsStrategy(ipv6Only: Boolean) = if (ipv6Only) "ipv6_only" else "ipv4_only"

internal fun dnsLookupPreference(strategy: String?) = when (strategy) {
    "ipv4_only" -> "prefer_ipv4"
    "ipv6_only" -> "prefer_ipv6"
    else -> strategy
}

/** Final production boundary: expand address-family policy without legacy DNS actions.
 * Preferences order internal lookup results (dns.strategy/domain_resolver.strategy).
 * On-wire A/AAAA have no address ordering; only-mode must answer the other family
 * with empty NOERROR, rather than falling through to another DNS server.
 */
internal fun finalizeGeneratedConfig(config: MutableMap<String, Any?>): MutableMap<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    val dns = config["dns"] as? MutableMap<String, Any?> ?: return config
    val finalPolicy = dns.remove("_neko_final_dns_policy")
    val rules = dns["rules"] as? List<*> ?: emptyList<Any>()
    resolveGeneratedInbounds(config, rules)
    val expanded = mutableListOf<Map<String, Any?>>()
    rules.forEachIndexed { index, value ->
        @Suppress("UNCHECKED_CAST")
        val rule = (value as? Map<String, Any?>)?.toMutableMap()
            ?: error("dns.rules[$index]: expected object")
        val policy = rule.remove(DNS_POLICY) as? String
        if (policy != null && (rule["action"] == null || rule["action"] == "route")) {
            require(!rule.containsKey("strategy")) {
                "dns.rules[$index].strategy: replace the legacy action with query_type/ip_version rules"
            }
            require(policy in setOf("as_is", "", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")) {
                "dns.rules[$index]: unsupported address-family policy"
            }
            val excluded = when (policy) { "ipv4_only" -> "AAAA"; "ipv6_only" -> "A"; else -> null }
            if (excluded != null) {
                val conditions = rule.filterKeys { it !in setOf("action", "server", "disable_cache", "rewrite_ttl",
                    "timeout", "client_subnet", "remove_client_subnet", "disable_optimistic_cache", "speculative", "race") }
                val guard = if (conditions.isEmpty()) {
                    mutableMapOf<String, Any?>("query_type" to listOf(excluded))
                } else {
                    mutableMapOf<String, Any?>("type" to "logical", "mode" to "and", "rules" to listOf(conditions, mapOf("query_type" to listOf(excluded))))
                }
                guard["action"] = "predefined"
                guard["rcode"] = "NOERROR"
                expanded += guard
            }
        }
        expanded += rule
    }
    val denied = when (finalPolicy) { "ipv4_only" -> "AAAA"; "ipv6_only" -> "A"; else -> null }
    if (denied != null) expanded += mapOf("query_type" to listOf(denied), "action" to "predefined", "rcode" to "NOERROR")
    dns["rules"] = expanded
    return config
}

/** DNS action preferences used to override inbound resolution preferences.
 * Represent that ordering with first-match, non-overlapping route.resolve
 * actions. Keep server unset so modern/custom DNS rules still select transport.
 */
private fun resolveGeneratedInbounds(config: MutableMap<String, Any?>, dnsRules: List<*>) {
    val incoming = config["inbounds"] as? List<*> ?: return
    val prefix = mutableListOf<Map<String, Any?>>()
    val known = setOf("", "as_is", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
    val actionKeys = setOf("action", "server", "disable_cache", "rewrite_ttl", "timeout", "client_subnet",
        "remove_client_subnet", "disable_optimistic_cache", "speculative", "race", DNS_POLICY, "rcode", "answer", "ns", "extra")
    val routeKeys = setOf("inbound", "domain", "domain_suffix", "domain_keyword", "domain_regex", "user_id",
        "rule_set", "invert", "process_name", "process_path", "package_name", "source_ip_cidr", "source_port", "source_port_range")
    config["inbounds"] = incoming.mapIndexed { index, item ->
        @Suppress("UNCHECKED_CAST")
        val inbound = (item as? Map<String, Any?>)?.toMutableMap() ?: return@mapIndexed item
        val strategy = inbound["domain_strategy"] as? String ?: return@mapIndexed inbound
        if (strategy !in known) return@mapIndexed inbound // strict native diagnostic
        inbound.remove("domain_strategy")
        if (strategy == "" || strategy == "as_is") return@mapIndexed inbound
        val tag = (inbound["tag"] as? String)?.takeIf { it.isNotEmpty() } ?: index.toString()
        val previous = mutableListOf<Map<String, Any?>>()
        var terminal = false
        fun resolve(condition: Map<String, Any?>, preference: String) {
            val clauses = mutableListOf<Map<String, Any?>>(mapOf("inbound" to listOf(tag)))
            if (condition.isNotEmpty()) clauses += condition
            if (previous.isNotEmpty()) clauses += mapOf("type" to "logical", "mode" to "or", "rules" to previous.toList(), "invert" to true)
            prefix += mapOf("type" to "logical", "mode" to "and", "rules" to clauses,
                "action" to "resolve", "strategy" to preference)
        }
        for (value in dnsRules) {
            if (terminal) break
            @Suppress("UNCHECKED_CAST")
            val rule = value as? Map<String, Any?> ?: continue
            if (rule["server"] == "dns-fake") continue // internal resolution must not use FakeIP
            val action = rule["action"] as? String
            if (action != null && action != "route") continue
            val condition = rule.filterKeys { it !in actionKeys }
            if (condition.keys.any { it !in routeKeys }) continue
            val policy = (rule[DNS_POLICY] as? String)?.takeIf { it.isNotEmpty() && it != "as_is" } ?: strategy
            resolve(condition, policy)
            if (condition.isEmpty()) terminal = true else previous += condition
        }
        if (!terminal) resolve(emptyMap(), strategy)
        inbound
    }
    if (prefix.isNotEmpty()) {
        @Suppress("UNCHECKED_CAST")
        val route = (config["route"] as? Map<String, Any?>)?.toMutableMap() ?: mutableMapOf()
        route["rules"] = prefix + (route["rules"] as? List<*> ?: emptyList<Any>())
        config["route"] = route
    }
}
