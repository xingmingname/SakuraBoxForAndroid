package moe.matsuri.nb4a

import moe.matsuri.nb4a.SingBoxOptions.*
import org.junit.Assert.*
import org.junit.Test

class RouteGenerationTest {
    @Test fun regexpEscapesRemainCaseSensitive() {
        val expression = "^\\D+[A-Z]$"
        val route = Rule_DefaultOptions().apply { makeSingBoxRule(listOf("regexp:$expression"), false) }
        val dns = DNSRule_DefaultOptions().apply { makeSingBoxRule(listOf("regexp:$expression")) }
        assertEquals(listOf(expression), route.domain_regex)
        assertEquals(listOf(expression), dns.domain_regex)
    }
    @Test fun domainAndIPRuleSetsRemainCombined() {
        val rule = Rule_DefaultOptions().apply {
            makeSingBoxRule(listOf("geosite:cn"), false)
            makeSingBoxRule(listOf("geoip:cn"), true)
        }
        assertEquals(listOf("geosite:cn", "geoip:cn"), rule.rule_set)
        val sets = mutableListOf<RuleSet>()
        generateRuleSet(rule.rule_set, sets)
        assertEquals(2, sets.size)
    }
    @Test fun singleConditionRulesAreNotDiscarded() {
        val rules = listOf(
            Rule_DefaultOptions().apply { port_range = listOf("1000:2000") },
            Rule_DefaultOptions().apply { source_port = listOf(1234) },
            Rule_DefaultOptions().apply { source_port_range = listOf("1000:2000") },
            Rule_DefaultOptions().apply { network = listOf("udp") },
            Rule_DefaultOptions().apply { protocol = listOf("tls") },
            Rule_DefaultOptions().apply { makeSingBoxRule(listOf("geoip:private"), true) },
        )
        assertTrue(Rule_DefaultOptions().checkEmpty())
        rules.forEach { assertFalse(it.checkEmpty()) }
        assertFalse(DNSRule_DefaultOptions().apply { _hack_custom_config = """{"domain":["example.invalid"]}""" }.checkEmpty())
    }
}
