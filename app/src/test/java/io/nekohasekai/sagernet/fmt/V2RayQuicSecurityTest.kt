package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.HttpUrl.Companion.toHttpUrl

class V2RayQuicSecurityTest {
    private val protocols = listOf("vless", "vmess", "trojan")
    private val key = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"

    private fun bean(protocol: String, transport: String, mode: String): StandardV2RayBean =
        (if (protocol == "trojan") TrojanBean().apply { password = "synthetic" }
        else VMessBean().apply { alterId = if (protocol == "vless") -1 else 0 }).apply {
            initializeDefaultValues()
            serverAddress = "127.0.0.1"; serverPort = 443; sni = "example.invalid"
            uuid = "00000000-0000-4000-8000-000000000001"
            encryption = if (protocol == "vmess") "auto" else ""
            type = transport; path = if (transport == "grpc") "synthetic-service" else "/test"
            security = if (mode == "none") "none" else "tls"
            if (mode == "utls") utlsFingerprint = "chrome"
            if (mode == "reality") {
                realityPubKey = key; realityShortId = "00"
                ConnectionSecurity.select(this, ConnectionSecurity.Mode.REALITY)
            }
        }

    private fun output(bean: StandardV2RayBean): MutableMap<String, Any?> {
        val out = buildSingBoxOutboundStandardV2RayBean(bean, false)
        ProtocolOptions.applyTo(out, bean)
        out._hack_custom_config = bean.customOutboundJson
        out._hack_config_map["tag"] = "node"
        return SingBoxOptions.MyOptions().apply { outbounds = listOf(out) }.asMap()
    }

    @Test fun allTransportSecurityCombinationsSerializeWithoutEditorPolicyRejection() {
        for(p in protocols)for(t in listOf("tcp","ws","http","grpc","httpupgrade","quic"))for(mode in listOf("none","tls","utls","reality")) {
            val node=bean(p,t,mode);val before=KryoConverters.serialize(node)
            val config=finalizeGeneratedConfig(output(node))
            val out=(config["outbounds"] as List<*>).single() as Map<*,*>
            if(t=="quic")assertEquals("quic",(out["transport"] as Map<*,*>)["type"])
            assertArrayEquals(before,KryoConverters.serialize(node))
            assertEquals(mode=="none",out["tls"]==null)
        }
    }

    @Test fun transportAndSecuritySwitchesKeepInactiveValues() {
        for(p in protocols) {
            val node=bean(p,"tcp","reality")
            node.protocolOptionsJson="""{"tls":{"reality":{"enabled":true},"handshake_timeout":"5s"},"future":{"kept":false}}"""
            node.type="quic"
            val reopened=node.clone() as StandardV2RayBean
            assertEquals(output(node),output(reopened))
            assertEquals(key,reopened.realityPubKey)
            assertEquals("5s",ProtocolOptions.get(ProtocolOptions.parse(reopened.protocolOptionsJson),"tls.handshake_timeout")!!.asString)
            ConnectionSecurity.select(reopened,ConnectionSecurity.Mode.TLS)
            assertEquals(key,reopened.realityPubKey)
            reopened.type="ws";ConnectionSecurity.select(reopened,ConnectionSecurity.Mode.REALITY)
            assertEquals("ws",reopened.type)
        }
    }

    @Test fun explicitOutboundOverridesKeepTheirExistingMergePriority() {
        for(p in protocols) {
            val node=bean(p,"tcp","tls").apply { jsonMode=NodeJson.LEGACY;customOutboundJson="""{"transport":{"type":"quic"},"tls":{"utls":{"enabled":true,"fingerprint":"chrome"}}}""" }
            val out=((finalizeGeneratedConfig(output(node))["outbounds"] as List<*>).single() as Map<*,*>)
            assertEquals("quic",(out["transport"] as Map<*,*>)["type"])
            assertEquals(true,((out["tls"] as Map<*,*>)["utls"] as Map<*,*>)["enabled"])
            assertEquals("tcp",node.type)
        }
    }

    @Test fun explicitFalseAndCustomTrueRemainDistinctWithoutClientRejection() {
        for(p in protocols) {
            val node=bean(p,"quic","utls").apply { jsonMode=NodeJson.LEGACY;protocolOptionsJson="""{"tls":{"utls":{"enabled":false}}}""" }
            fun enabled()=((((output(node)["outbounds"] as List<*>).single() as Map<*,*>)["tls"] as Map<*,*>)["utls"] as Map<*,*>)["enabled"]
            assertEquals(false,enabled())
            node.customOutboundJson="""{"tls":{"utls":{"enabled":true}}}"""
            assertEquals(true,enabled())
            assertTrue(node.protocolOptionsJson.contains("false"))
        }
    }

    @Test fun fullConfigurationOverridesReachTheNativeBoundary() {
        val config=output(bean("vless","quic","tls"))
        Util.mergeJSON(config,"""{"outbounds":[{"type":"vless","tag":"node","transport":{"type":"quic"},"tls":{"enabled":true,"reality":{"enabled":true}}}]}""")
        val before=Gson().toJson(config)
        finalizeGeneratedConfig(config)
        assertEquals(before,Gson().toJson(config))
    }

    @Test fun importedUrisRetainUnsupportedCombinationsBesideValidNeighbors() {
        val uri="vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?type=quic&security=none#legacy"
        val node=VMessBean().apply { initializeDefaultValues();alterId=-1;parseDuckSoft(uri.replace("vless://","https://").toHttpUrl());initializeDefaultValues() }
        assertEquals("quic",node.type)
        assertEquals(ConnectionSecurity.Mode.NONE,ConnectionSecurity.mode(node))
        val copy=parseUniversal(node.toUniversalLink()) as StandardV2RayBean
        assertEquals("quic",copy.type)
        assertEquals(output(node),output(copy))
    }

    @Test fun unrelatedOutboundTypesAreNotRewrittenByFinalization() {
        for(p in listOf("hysteria","hysteria2","tuic","anytls","http","shadowtls")) {
            val config=mutableMapOf<String,Any?>("outbounds" to listOf(mapOf("type" to p,"transport" to mapOf("type" to "quic"))))
            val before=Gson().toJson(config);finalizeGeneratedConfig(config)
            assertEquals(before,Gson().toJson(config))
        }
    }


}
