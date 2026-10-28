package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class NodeJsonTest {
    private val outbound = """{"type":"vless","tag":"user-tag","server":"server-b.invalid","server_port":8443,"uuid":"00000000-0000-4000-8000-000000000001","tls":{"enabled":true,"server_name":"example.invalid","utls":{"enabled":true,"fingerprint":"firefox"}}}"""
    private val full = """{"log":{"disabled":true},"inbounds":[{"type":"tun","tag":"tun-in","address":["172.19.0.1/30"],"stack":"system"}],"outbounds":[$outbound],"route":{"final":"user-tag"}}"""
    private fun bean() = VMessBean().apply {
        initializeDefaultValues(); alterId = -1; serverAddress = "server-a.invalid"; serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"; utlsFingerprint = "chrome"
    }
    private fun invalid(kind: NodeJson.ErrorKind, call: () -> Unit) {
        val ex = assertThrows(NodeJson.Invalid::class.java, call)
        assertEquals(kind, ex.kind)
    }
    @Test fun independentModesPreserveBothDocumentsAndOriginalNode() {
        val node = bean()
        NodeJson.set(node, NodeJson.OUTBOUND, outbound)
        NodeJson.set(node, NodeJson.FULL, full)
        node.jsonMode = NodeJson.NONE
        val copy = node.clone() as VMessBean
        assertEquals(NodeJson.state(node), NodeJson.state(copy))
        assertFalse(NodeJson.enabled(copy))
        assertEquals("server-a.invalid", copy.serverAddress); assertEquals(443, copy.serverPort)
        assertEquals("chrome", copy.utlsFingerprint)
        assertEquals(outbound, copy.customOutboundJson); assertEquals(full, copy.customConfigJson)
        copy.jsonMode = NodeJson.OUTBOUND
        assertEquals("server-b.invalid" to 8443, NodeJson.tcpTarget(copy))
        copy.jsonMode = NodeJson.FULL
        assertEquals("server-b.invalid" to 8443, NodeJson.tcpTarget(copy))
        copy.jsonMode = NodeJson.NONE
        assertEquals("server-a.invalid" to 443, NodeJson.tcpTarget(copy))
    }
    @Test fun commonBlobModeRoundTripAcrossProtocols() {
        val types = listOf<AbstractBean>(bean(), io.nekohasekai.sagernet.fmt.trojan.TrojanBean(),
            io.nekohasekai.sagernet.fmt.http.HttpBean(), io.nekohasekai.sagernet.fmt.socks.SOCKSBean(),
            io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean(), io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean(),
            io.nekohasekai.sagernet.fmt.tuic.TuicBean(), io.nekohasekai.sagernet.fmt.ssh.SSHBean(),
            io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean(), moe.matsuri.nb4a.proxy.anytls.AnyTLSBean(),
            moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean(), moe.matsuri.nb4a.proxy.config.ConfigBean(),
            io.nekohasekai.sagernet.fmt.internal.ChainBean())
        for (node in types) for (mode in listOf(NodeJson.NONE, NodeJson.OUTBOUND, NodeJson.FULL, NodeJson.LEGACY)) {
            node.initializeDefaultValues(); node.customOutboundJson = outbound; node.customConfigJson = full; node.jsonMode = mode
            assertEquals(NodeJson.state(node), NodeJson.state(node.clone()))
        }
    }
    @Test fun ordinaryEditsCannotOverwriteTheIndependentJsonState() {
        val stale = bean()
        val latest = stale.clone().apply { NodeJson.set(this, NodeJson.OUTBOUND, outbound) }
        stale.serverAddress = "ordinary-edited.invalid"
        NodeJson.copyState(latest, stale)
        assertEquals(NodeJson.state(latest), NodeJson.state(stale))
        assertEquals("ordinary-edited.invalid", stale.serverAddress)
        assertEquals("server-a.invalid", latest.serverAddress)
    }
    @Test fun bothSavedDocumentsShareOneDatabaseRowBudget() {
        val node = bean().apply { customOutboundJson = "x".repeat(600_000); customConfigJson = "y".repeat(600_000) }
        invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.checkStorage(node) }
        node.customConfigJson = ""
        NodeJson.checkStorage(node)
    }
    @Test fun legacyPartialOverridesRemainEnabledWithoutReinterpretation() {
        for (version in 1..2) {
            val node = bean().apply { customOutboundJson = """{"tls":{"server_name":"legacy.invalid"}}""" }
            val bytes = ByteArrayOutputStream()
            ByteBufferOutput(bytes).use { out ->
                node.serialize(out); out.writeInt(version); out.writeString(node.name)
                out.writeString(node.customOutboundJson); out.writeString(node.customConfigJson)
                if (version == 2) out.writeString(node.protocolOptionsJson)
                out.flush()
            }
            val old = KryoConverters.deserialize(VMessBean(), bytes.toByteArray())
            assertEquals(NodeJson.LEGACY, old.jsonMode); assertTrue(NodeJson.enabled(old))
            assertEquals(1, old.jsonLegacyMask)
            assertEquals(node.customOutboundJson, NodeJson.legacyOutbound(old))
            old.jsonMode = NodeJson.NONE
            assertEquals(1, old.clone().jsonLegacyMask)
            assertEquals("", NodeJson.legacyOutbound(old)); assertEquals("", NodeJson.legacyFull(old))
        }
    }
    @Test fun emptyLegacyNodesRemainOrdinary() {
        val node = bean(); val bytes = ByteArrayOutputStream()
        ByteBufferOutput(bytes).use { out ->
            node.serialize(out); out.writeInt(2); out.writeString(node.name); out.writeString(""); out.writeString(""); out.writeString(""); out.flush()
        }
        assertEquals(NodeJson.NONE, KryoConverters.deserialize(VMessBean(), bytes.toByteArray()).jsonMode)
    }
    @Test fun strictSyntaxRejectsCommentsTrailingValuesAndWrongShape() {
        for (text in listOf("{", "{a:1}", "{\"a\":1,}", "{/*x*/\"a\":1}", "{\"a\":1} {}", "{\"a\":NaN}"))
            invalid(NodeJson.ErrorKind.SYNTAX) { NodeJson.parse(text) }
        for (text in listOf("[]", "null", "42", "\"string\"")) invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.parse(text) }
    }
    @Test fun syntaxErrorDoesNotEchoTheInput() {
        val secret = "SYNTHETIC_PRIVATE_INPUT"
        val error = assertThrows(NodeJson.Invalid::class.java) { NodeJson.parse("{\"a\":$secret}") }
        assertFalse(error.message!!.contains(secret))
    }
    @Test fun duplicateKeysCannotHaveTwoMeanings() {
        invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.parse("""{"tls":{"enabled":true,"enabled":false}}""") }
        val root = NodeJson.parse("""{"a":false,"b":0,"c":"","d":null}""")
        assertFalse(root["a"].asBoolean); assertEquals(0, root["b"].asInt); assertEquals("", root["c"].asString); assertTrue(root["d"].isJsonNull)
    }
    @Test fun sizeAndDepthBoundsAreEnforced() {
        invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.parse("{\"a\":\"" + "中".repeat(NodeJson.MAX_BYTES / 2) + "\"}") }
        invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.parse("[".repeat(65) + "0" + "]".repeat(65)) }
        assertEquals("[{}]", NodeJson.parse("{\"x\":\"[{}]\"}")["x"].asString)
    }
    @Test fun outboundReplacesSourceButKeepsApplicationRoutingIdentity() {
        val root = NodeJson.outbound(outbound, "c-7-9")
        assertEquals("c-7-9", root["tag"].asString)
        assertEquals(8443, root["server_port"].asInt)
        assertEquals("firefox", root.getAsJsonObject("tls").getAsJsonObject("utls")["fingerprint"].asString)
        invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.outbound(full, "proxy") }
        invalid(NodeJson.ErrorKind.MISSING) { NodeJson.outbound("{}", "proxy") }
    }
    @Test fun fullConfigRetainsUserTunDnsAndRoute() {
        val root = NodeJson.parse(full)
        NodeJson.validateReferences(root); NodeJson.requireVpn(root, true)
        assertEquals("system", root.getAsJsonArray("inbounds")[0].asJsonObject["stack"].asString)
        assertEquals("user-tag", root.getAsJsonObject("route")["final"].asString)
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { dir -> File(dir, "node-json").mkdirs(); File(dir, "node-json/node-json-full.json").writeText(NodeJson.text(root)) }
    }
    @Test fun missingTunDoesNotPretendToCreateVpn() {
        val root = NodeJson.parse("""{"outbounds":[{"type":"direct","tag":"d"}]}""")
        invalid(NodeJson.ErrorKind.MISSING) { NodeJson.requireVpn(root, true) }
        NodeJson.requireVpn(root, false)
    }
    @Test fun fullModeTestRequiresAnExplicitUnambiguousTarget() {
        val root = NodeJson.parse(full)
        assertEquals("user-tag", NodeJson.string(NodeJson.fullTestOutbound(root), "tag"))
        root.getAsJsonArray("outbounds").add(NodeJson.parse("""{"type":"direct","tag":"d"}"""))
        root.remove("route")
        invalid(NodeJson.ErrorKind.REFERENCE) { NodeJson.fullTestOutbound(root) }
        root.add("route", NodeJson.parse("""{"final":"d"}"""))
        invalid(NodeJson.ErrorKind.REFERENCE) { NodeJson.fullTestOutbound(root) }
    }
    @Test fun testProjectionDoesNotStartUserListenersOrManagementServices() {
        val root = NodeJson.parse(full)
        root.add("experimental", NodeJson.parse("""{"clash_api":{"external_controller":"127.0.0.1:9999"}}"""))
        val projected = NodeJson.testConfig(root)
        assertEquals(0, projected.getAsJsonArray("inbounds").size()); assertFalse(projected.has("experimental"))
        assertEquals(root["outbounds"], projected["outbounds"])
        assertTrue(root.has("experimental")); assertEquals(1, root.getAsJsonArray("inbounds").size())
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { dir -> File(dir, "node-json").mkdirs(); File(dir, "node-json/node-json-test.json").writeText(NodeJson.text(projected)) }
    }
    @Test fun duplicateAndUnknownTagsAndDetourCyclesAreRejected() {
        for (json in listOf(
            """{"outbounds":[{"type":"direct","tag":"d"},{"type":"direct","tag":"d"}]}""",
            """{"outbounds":[{"type":"direct","tag":"d","detour":"missing"}]}""",
            """{"outbounds":[{"type":"direct","tag":"d","detour":"e"},{"type":"direct","tag":"e","detour":"d"}]}""",
            """{"outbounds":[{"type":"direct","tag":"d"}],"route":{"final":"missing"}}""",
            """{"outbounds":[{"type":"selector","tag":"s","outbounds":["missing"]}]}"""
        )) invalid(NodeJson.ErrorKind.REFERENCE) { NodeJson.validateReferences(NodeJson.parse(json)) }
    }
    @Test fun requiredFieldsAndTypesAreChecked() {
        for (port in listOf("0", "65536", "443.5", "\"443\""))
            invalid(NodeJson.ErrorKind.MISSING) { NodeJson.validateReferences(NodeJson.parse("""{"outbounds":[{"type":"http","server":"example.invalid","server_port":$port}]}""")) }
        invalid(NodeJson.ErrorKind.MISSING) { NodeJson.validateReferences(NodeJson.parse("{}")) }
        invalid(NodeJson.ErrorKind.STRUCTURE) { NodeJson.validateReferences(NodeJson.parse("""{"outbounds":{}}""")) }
    }
    @Test fun explicitOutboundSurvivesGlobalPatchWithoutChangingOtherNodes() {
        val config = mutableMapOf<String, Any?>("outbounds" to listOf(mapOf("tag" to "proxy", "type" to "direct"), mapOf("tag" to "other", "type" to "direct")))
        val source = mapOf<String, Any?>("tag" to "proxy", "type" to "http", "server" to "server-b.invalid", "server_port" to 8443)
        NodeJson.restoreOutbounds(config, mapOf("proxy" to source))
        assertEquals(listOf(source, mapOf("tag" to "other", "type" to "direct")), config["outbounds"])
        config["outbounds"] = emptyList<Any>()
        NodeJson.restoreOutbounds(config, mapOf("proxy" to source)); assertEquals(listOf(source), config["outbounds"])
    }
    @Test fun wireguardSourceUsesEndpointCollection() {
        val root = mutableMapOf<String, Any?>()
        NodeJson.restoreOutbounds(root, mapOf("proxy" to mapOf("tag" to "proxy", "type" to "wireguard")))
        assertTrue(root.containsKey("endpoints")); assertFalse(root.containsKey("outbounds"))
    }
}
