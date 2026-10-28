package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundTLS
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.importAnyTLSOutbound
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TlsCompatibilityTest {
    private val pin = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private fun config(value: String, type: String = "anytls") =
        json("""{"outbounds":[{"type":"$type","tls":$value}]}""")
    private fun pins(bytes: Int) = Base64.getEncoder().encodeToString(ByteArray(bytes) { 7 })
    private fun nodes(): List<AbstractBean> = listOf(
        AnyTLSBean(), VMessBean().apply { alterId = -1 }, VMessBean(), TrojanBean(), HttpBean(),
        ShadowTLSBean(), HysteriaBean().apply { protocolVersion = 1 },
        HysteriaBean().apply { protocolVersion = 2 }, TuicBean().apply { protocolVersion = 5 }
    ).onEach {
        it.initializeDefaultValues(); it.serverAddress = "example.invalid"; it.serverPort = 443
        if (it is StandardV2RayBean) { it.security = "tls"; it.uuid = "00000000-0000-0000-0000-000000000001" }
        if (it is HysteriaBean) { it.protocol = HysteriaBean.PROTOCOL_UDP; it.serverPorts = "443" }
    }
    private fun certificate(bean: AbstractBean, value: String) {
        when (bean) {
            is AnyTLSBean -> bean.certificates = value
            is StandardV2RayBean -> bean.certificates = value
            is HysteriaBean -> bean.caText = value
            is TuicBean -> bean.caText = value
        }
    }
    private fun outbound(bean: AbstractBean): JsonObject {
        val dto = when (bean) {
            is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
            is ShadowTLSBean -> SingBoxOptions.Outbound_ShadowTLSOptions().apply {
                type = "shadowtls"; tls = buildSingBoxOutboundTLS(bean, false)
            }
            is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean, false)
            is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean, false)
            is TuicBean -> buildSingBoxOutboundTuicBean(bean, false)
            else -> error("fixture")
        }
        ProtocolOptions.applyTo(dto, bean)
        dto._hack_custom_config = NodeJson.legacyOutbound(bean)
        return Gson().toJsonTree(dto.asMap()).asJsonObject
    }
    private fun validate(bean: AbstractBean) = outbound(bean)

    @Test fun emptyValuesAreInactiveAcrossAllNineProtocols() {
        for (bean in nodes()) for (empty in listOf("null", "\"\"", "[]", "[\"\"]", "[\"\",\"\"]")) {
            certificate(bean, "")
            bean.protocolOptionsJson = """{"tls":{"certificate":$empty,"certificate_path":"","certificate_public_key_sha256":["$pin"]}}"""
            val before = KryoConverters.serialize(bean)
            validate(bean)
            assertArrayEquals(before, KryoConverters.serialize(bean))
            certificate(bean, "synthetic-ca")
            bean.protocolOptionsJson = """{"tls":{"certificate_public_key_sha256":$empty}}"""
            validate(bean)
        }
    }

    @Test fun certificatesAndPinsAreStoredTogetherForNativeValidation() {
        for (bean in nodes()) {
            certificate(bean, "synthetic-ca")
            bean.protocolOptionsJson = """{"tls":{"certificate_public_key_sha256":["$pin"]}}"""
            val before=KryoConverters.serialize(bean)
            val tls=outbound(bean.clone()).getAsJsonObject("tls")
            if (bean !is ShadowTLSBean) assertEquals("synthetic-ca", tls["certificate"].asString)
            assertEquals(pin,tls["certificate_public_key_sha256"].asJsonArray.single().asString)
            assertArrayEquals(before,KryoConverters.serialize(bean))
        }
    }

    @Test fun finalAdvancedAndLegacyOverridesRetainTheirExistingPriority() {
        for (bean in nodes()) {
            certificate(bean,"synthetic-ca")
            bean.protocolOptionsJson="""{"tls":{"certificate":[],"certificate_public_key_sha256":["$pin"]}}"""
            assertEquals(0,outbound(bean).getAsJsonObject("tls")["certificate"].asJsonArray.size())
            bean.jsonMode=NodeJson.LEGACY;bean.customOutboundJson="""{"tls":{"certificate":["overridden-ca"]}}"""
            val tls=outbound(bean).getAsJsonObject("tls")
            assertEquals("overridden-ca",tls["certificate"].asJsonArray.single().asString)
            assertEquals(pin,tls["certificate_public_key_sha256"].asJsonArray.single().asString)
        }
    }

    @Test fun pinTextIsStoredWithoutClientSideDigestValidation() {
        val field=ProtocolOptions.Field("tls.certificate_public_key_sha256",ProtocolOptions.Kind.PINS)
        for (value in listOf(pins(2),pins(31),pins(32),pins(33),"not@@@",pin.dropLast(1),"sha256/$pin")) {
            val root=ProtocolOptions.set(JsonObject(),field,value)
            assertEquals(value,root["tls"].asJsonObject["certificate_public_key_sha256"].asJsonArray.single().asString)
            assertEquals(root,JsonParser.parseString(root.toString()))
        }
    }

    @Test fun bothJsonModesRetainPinDocumentsWithoutMutatingSavedData() {
        for(mode in listOf(NodeJson.OUTBOUND,NodeJson.FULL))for(size in listOf(2,31,32,33)) {
            val bean=nodes().first()
            val out="""{"type":"anytls","tls":{"enabled":true,"certificate_public_key_sha256":["${pins(size)}"]}}"""
            val document=if(mode==NodeJson.OUTBOUND)out else """{"outbounds":[$out]}"""
            NodeJson.set(bean,mode,document)
            val copy=bean.clone()
            assertEquals(document,NodeJson.saved(copy,mode));assertEquals(NodeJson.state(bean),NodeJson.state(copy))
        }
    }

    @Test fun multiplePinsAndEmptyRepresentationsRemainUnchanged() {
        for(value in listOf("null","\"\"","[]","[\"\"]","[\"$pin\",\"${pins(32)}\"]")) {
            val bean=nodes().first().apply { protocolOptionsJson="""{"tls":{"certificate_public_key_sha256":$value}}""" }
            val saved = bean.protocolOptionsJson
            val expected = JsonParser.parseString(value)
            val output = outbound(bean).getAsJsonObject("tls")
            if (expected.isJsonNull) {
                assertFalse(output.has("certificate_public_key_sha256"))
            } else {
                assertEquals(expected, output["certificate_public_key_sha256"])
            }
            assertEquals(saved, bean.protocolOptionsJson)
            assertEquals(saved, bean.clone().protocolOptionsJson)
        }
    }

    @Test fun customTlsValuesAreNotCoercedOrDiscarded() {
        for(field in listOf("certificate","certificate_path","certificate_public_key_sha256"))
            for(value in listOf("true","42","{}")) {
                val bean=nodes().first().apply { protocolOptionsJson="""{"tls":{"$field":$value}}""" }
                val result=outbound(bean).getAsJsonObject("tls")
                assertEquals(JsonParser.parseString(value),result[field])
            }
    }

    @Test fun inactiveTlsValuesSurviveCloningAndJsonModeStorage() {
        val bean=nodes().first()
        val document="""{"type":"anytls","tls":{"enabled":false,"certificate_public_key_sha256":["bad"]}}"""
        NodeJson.set(bean,NodeJson.OUTBOUND,document)
        assertEquals(document,NodeJson.saved(bean.clone(),NodeJson.OUTBOUND))
        bean.jsonMode=NodeJson.NONE
        assertEquals(document,bean.clone().customOutboundJson)
    }

    @Test fun engineAndTransportChoicesReachTheNativeConfigurationUnchanged() {
        for(bean in nodes()) {
            bean.protocolOptionsJson="""{"tls":{"engine":"windows","utls":{"enabled":true,"fingerprint":"chrome"}}}"""
            val before=bean.protocolOptionsJson
            val tls=outbound(bean).getAsJsonObject("tls")
            assertEquals("windows",tls["engine"].asString)
            assertTrue(tls["utls"].asJsonObject["enabled"].asBoolean)
            assertEquals(before,bean.protocolOptionsJson)
        }
    }

    @Test fun modesKeepDormantDataAndDoNotApplyPinsToReality() {
        for (bean in nodes().filter { it is AnyTLSBean || it is StandardV2RayBean && it !is ShadowTLSBean }) {
            certificate(bean, "synthetic-ca")
            bean.protocolOptionsJson = """{"tls":{"certificate_public_key_sha256":["bad"],"reality":{"public_key":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc","short_id":""}}}"""
            ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY)
            val saved = bean.protocolOptionsJson
            validate(bean)
            val tls = outbound(bean).getAsJsonObject("tls")
            assertFalse(tls.has("certificate")); assertFalse(tls.has("certificate_public_key_sha256"))
            assertEquals(saved, bean.protocolOptionsJson)
            if (ConnectionSecurity.Mode.NONE in ConnectionSecurity.supported(bean)) {
                ConnectionSecurity.select(bean, ConnectionSecurity.Mode.NONE); validate(bean)
                assertFalse(outbound(bean).has("tls"))
            }
            ConnectionSecurity.select(bean, ConnectionSecurity.Mode.TLS)
            assertEquals("bad",outbound(bean).getAsJsonObject("tls")["certificate_public_key_sha256"].asJsonArray.single().asString)
        }
    }

    @Test fun anyTlsImportCloneReopenAndPartialEditKeepTheExactValues() {
        val bean = importAnyTLSOutbound("""{"type":"anytls","server":"example.invalid","server_port":443,"password":"synthetic","tls":{"enabled":true,"certificate":"synthetic-ca","certificate_public_key_sha256":[]}}""")!!
        validate(bean)
        val copy = bean.clone()
        assertEquals(bean.certificates,copy.certificates); assertEquals(bean.protocolOptionsJson,copy.protocolOptionsJson)
        assertEquals(outbound(bean),outbound(copy))
        val field = ProtocolOptions.fields(copy).first { it.path=="tls.certificate_public_key_sha256" }
        var root = ProtocolOptions.parse(copy.protocolOptionsJson)
        root = ProtocolOptions.set(root,ProtocolOptions.fields(copy).first { it.path=="connect_timeout" },"15s")
        root = ProtocolOptions.set(root,field,pin)
        copy.certificates = ""; copy.protocolOptionsJson = root.toString()
        validate(copy)
        assertEquals(pin,ProtocolOptions.text(ProtocolOptions.parse(copy.clone().protocolOptionsJson),field))
        root = ProtocolOptions.set(root,field,""); assertEquals("15s",root["connect_timeout"].asString)
    }

    @Test fun tlsEditingDoesNotOverwriteServerCredentials() {
        val bean=nodes().first() as AnyTLSBean
        bean.password="synthetic-auth"
        bean.protocolOptionsJson="""{"tls":{"client_key":"invalid synthetic input"}}"""
        outbound(bean)
        val copy=bean.clone()
        assertEquals("synthetic-auth",copy.password)
        assertEquals(bean.protocolOptionsJson,copy.protocolOptionsJson)
    }

    @Test fun recursiveMergePreservesExplicitNullWithoutMutatingTheSource() {
        val source = mapOf("tls" to mapOf("certificate" to null, "certificate_path" to "", "enabled" to true))
        val destination = mutableMapOf<String, Any?>("tls" to mapOf("certificate" to "old-ca", "server_name" to "example.invalid"))
        moe.matsuri.nb4a.utils.Util.mergeMap(destination, source)
        val tls = destination["tls"] as Map<*, *>
        assertTrue(tls.containsKey("certificate")); assertNull(tls["certificate"])
        assertEquals("example.invalid", tls["server_name"])
        assertEquals(source["tls"], mapOf("certificate" to null, "certificate_path" to "", "enabled" to true))
    }

    @Test fun tlsDurationAndCurveTextRemainAvailableForCoreValidation() {
        val root=ProtocolOptions.set(JsonObject(),ProtocolOptions.Field("tls.handshake_timeout",ProtocolOptions.Kind.DURATION),"not-a-duration")
        val result=ProtocolOptions.set(root,ProtocolOptions.Field("tls.curve_preferences",ProtocolOptions.Kind.LIST,choices=listOf("X25519")),"custom-curve")
        assertEquals("not-a-duration",ProtocolOptions.get(result,"tls.handshake_timeout")!!.asString)
        assertEquals("custom-curve",ProtocolOptions.get(result,"tls.curve_preferences")!!.asJsonArray.single().asString)
        assertEquals("not-a-duration",ProtocolOptions.get(root,"tls.handshake_timeout")!!.asString)
    }


}

