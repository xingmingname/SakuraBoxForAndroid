package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class MtlsOptionsTest {
    private val cert = "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n"
    private val key = "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n"
    private fun root(text: String) = JsonParser.parseString(text).asJsonObject
    private fun nodes() = listOf<AbstractBean>(AnyTLSBean(), VMessBean().apply { alterId = -1 }, VMessBean(), TrojanBean(), HttpBean()).onEach {
        it.initializeDefaultValues(); it.serverAddress = "example.invalid"; it.serverPort = 443
        if (it is StandardV2RayBean) { it.security = "tls"; it.uuid = "00000000-0000-0000-0000-000000000001" }
    }
    private fun options() = ProtocolOptions.set(ProtocolOptions.set(JsonObject(), MtlsOptions.certificate, cert), MtlsOptions.key, key)
    @Test fun summariesDistinguishPresenceWithoutDisplayingValues() {
        for (v in listOf(null, "", " \t\r\n")) assertFalse(MtlsOptions.isSet(v))
        for (v in listOf(cert, key, "not-yet-valid")) assertTrue(MtlsOptions.isSet(v))
    }

    @Test fun pemEditingPreservesExactWhitespaceAndOtherOptions() {
        val text = "\r\n$cert  \n"
        val original = root("""{"tls":{"server_name":"example.invalid","client_key_path":"retained-path"},"connect_timeout":"3s"}""")
        val changed = ProtocolOptions.set(original, MtlsOptions.certificate, text)
        assertEquals(text, ProtocolOptions.text(changed, MtlsOptions.certificate))
        assertNull(ProtocolOptions.get(original, MtlsOptions.certificate.path))
        assertEquals("retained-path", ProtocolOptions.get(changed, "tls.client_key_path")!!.asString)
        assertEquals("3s", changed["connect_timeout"].asString)
        val copy = AnyTLSBean().apply { initializeDefaultValues(); protocolOptionsJson = changed.toString() }.clone()
        assertEquals(text, ProtocolOptions.text(ProtocolOptions.parse(copy.protocolOptionsJson), MtlsOptions.certificate))
    }

    @Test fun allSharedTlsPagesGenerateBothNativeFieldsAndKeepInsecureOff() {
        for (bean in nodes()) {
            assertTrue(MtlsOptions.supported(bean))
            bean.protocolOptionsJson = options().toString()
            val out = ProtocolOptions.validationOutbound(bean, options())!!
            assertEquals(cert, out.getAsJsonObject("tls")["client_certificate"].asString)
            assertEquals(key, out.getAsJsonObject("tls")["client_key"].asString)
            assertFalse(out.getAsJsonObject("tls")["insecure"]?.asBoolean == true)
            System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
                File(directory, "mtls").mkdirs()
                File(directory, "mtls/${ProtocolOptions.protocol(bean)}.json").writeText(out.toString())
            }
        }
    }

    @Test fun noIdentityKeepsOrdinaryTlsAndDoesNotCallNativeChecker() {
        for (bean in nodes()) {
            val out = ProtocolOptions.validationOutbound(bean, JsonObject())!!
            assertFalse(out.getAsJsonObject("tls").has("client_key"))
            assertFalse(out.getAsJsonObject("tls").has("client_certificate"))
            assertFalse(out.getAsJsonObject("tls")["insecure"]?.asBoolean == true)
        }
    }

    @Test fun partialClientIdentityCanBeSavedAndReopened() {
        for(field in listOf(MtlsOptions.certificate,MtlsOptions.key)) {
            val partial=ProtocolOptions.set(JsonObject(),field,"invalid synthetic PEM")
            val bean=nodes().first().apply { protocolOptionsJson=partial.toString() }
            val copy=bean.clone()
            assertEquals("invalid synthetic PEM",ProtocolOptions.text(ProtocolOptions.parse(copy.protocolOptionsJson),field))
            val out=ProtocolOptions.validationOutbound(copy,partial)!!.getAsJsonObject("tls")
            assertEquals("invalid synthetic PEM",out[field.path.substringAfter("tls.")].asString)
            assertFalse(out["insecure"]?.asBoolean==true)
        }
    }

    @Test fun deletingAndBlankInputAffectOnlyTheChosenField() {
        val both = options()
        val deleted = ProtocolOptions.set(both, MtlsOptions.key, " \n\t")
        assertNull(ProtocolOptions.get(deleted, MtlsOptions.key.path))
        assertEquals(cert, ProtocolOptions.text(deleted, MtlsOptions.certificate))
        assertEquals(key, ProtocolOptions.text(both, MtlsOptions.key))
        val empty = ProtocolOptions.set(deleted, MtlsOptions.certificate, "")
        assertEquals(0, empty.size())
    }

    @Test fun listablePemAndExistingJsonPathsRemainSupported() {
        val tls = options().getAsJsonObject("tls")
        tls.add("client_certificate", com.google.gson.JsonArray().apply { cert.lines().forEach { add(it) } })
        tls.add("client_key", com.google.gson.JsonArray().apply { add(key) })
        assertEquals(cert, ProtocolOptions.text(JsonObject().apply { add("tls", tls) }, MtlsOptions.certificate))
        assertEquals("cert-file",root("""{"client_certificate_path":"cert-file","client_key_path":"key-file"}""")["client_certificate_path"].asString)
        assertEquals(" ",root("""{"client_certificate_path":" ","client_key_path":"\t"}""")["client_certificate_path"].asString)
        val paths = root("""{"tls":{"client_certificate":" \n","client_key":"","client_certificate_path":"cert-file","client_key_path":"key-file"}}""")
        val effective = ProtocolOptions.effectiveOptions(nodes().first(), paths)
        assertFalse(effective.getAsJsonObject("tls").has("client_certificate"))
        assertEquals("cert-file", ProtocolOptions.get(effective, "tls.client_certificate_path")!!.asString)
        assertTrue(paths.getAsJsonObject("tls").has("client_certificate"))
    }

    @Test fun invalidPemAndCustomJsonTypesArePreservedForTheCore() {
        for(value in listOf("\"invalid synthetic PEM\"","42","{}")) {
            val bean=nodes().first().apply { protocolOptionsJson="""{"tls":{"client_key":$value}}""" }
            val out=ProtocolOptions.validationOutbound(bean,ProtocolOptions.parse(bean.protocolOptionsJson))!!
            assertEquals(JsonParser.parseString(value),ProtocolOptions.get(out,"tls.client_key"))
            assertEquals(bean.protocolOptionsJson,bean.clone().protocolOptionsJson)
        }
    }

    @Test fun modeSwitchesKeepStoredIdentityAndExistingRealityPassthrough() {
        val bean = nodes()[1] as VMessBean
        bean.protocolOptionsJson = options().toString()
        bean.realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"; bean.realityShortId = "00"
        for (mode in ConnectionSecurity.Mode.entries) {
            ConnectionSecurity.select(bean, mode)
            val stored = ProtocolOptions.parse(bean.protocolOptionsJson)
            assertEquals(key, ProtocolOptions.text(stored, MtlsOptions.key))
            val tls = ProtocolOptions.validationOutbound(bean, stored)!!.getAsJsonObject("tls")
            if (mode != ConnectionSecurity.Mode.NONE) assertEquals(key, tls["client_key"].asString)
            else assertTrue(tls == null || !tls.has("client_key"))
            val clone = bean.clone()
            assertEquals(bean.protocolOptionsJson, clone.protocolOptionsJson)
        }
    }

    @Test fun identityCaAndPinsArePassedThroughWithoutSaveConflicts() {
        val bean=nodes().first()
        val stored=ProtocolOptions.set(options(),ProtocolOptions.Field("tls.certificate_public_key_sha256",ProtocolOptions.Kind.PINS),"synthetic-pin")
        stored.getAsJsonObject("tls").addProperty("certificate",cert)
        bean.protocolOptionsJson=stored.toString()
        val out=ProtocolOptions.validationOutbound(bean,stored)!!.getAsJsonObject("tls")
        assertEquals(cert,out["client_certificate"].asString)
        assertEquals(key,out["client_key"].asString)
        assertEquals(cert,out["certificate"].asString)
        assertEquals("synthetic-pin",out["certificate_public_key_sha256"].asJsonArray.single().asString)
        assertFalse(out["insecure"]?.asBoolean==true)
    }

    @Test fun generatingIncompleteIdentityNeverMutatesTheStoredNode() {
        val bean=nodes().first().apply { protocolOptionsJson=ProtocolOptions.set(JsonObject(),MtlsOptions.key,"invalid synthetic PEM").toString() }
        val before=KryoConverters.serialize(bean)
        val out=ProtocolOptions.validationOutbound(bean,ProtocolOptions.parse(bean.protocolOptionsJson))!!
        assertEquals("invalid synthetic PEM",ProtocolOptions.get(out,"tls.client_key")!!.asString)
        assertNull(ProtocolOptions.get(out,"tls.client_certificate"))
        assertArrayEquals(before,KryoConverters.serialize(bean))
    }

    @Test fun fullAndOutboundJsonRetainIdentityAcrossModeDisableAndClone() {
        val bean = nodes().first()
        val outbound = ProtocolOptions.validationOutbound(bean, options())!!.toString()
        val full = JsonObject().apply { add("outbounds", com.google.gson.JsonArray().apply { add(JsonParser.parseString(outbound)) }) }.toString()
        NodeJson.set(bean, NodeJson.OUTBOUND, outbound)
        NodeJson.set(bean, NodeJson.FULL, full)
        bean.jsonMode = NodeJson.NONE
        val copy = bean.clone()
        assertEquals(outbound, copy.customOutboundJson); assertEquals(full, copy.customConfigJson)
        assertEquals(bean.serverAddress, copy.serverAddress)
        assertEquals(JsonParser.parseString(full),NodeJson.parse(full))
    }

    @Test fun clearAdvancedOptionsClearsBothIdentityFieldsButPreservesRealityIdentityAndOriginalInput() {
        for (bean in nodes()) {
            val original = options()
            original.getAsJsonObject("tls").add("reality", root("""{"enabled":false,"public_key":"retained-key","short_id":"00"}"""))
            val before = original.toString()
            val cleared = ConnectionSecurity.clearAdvancedOptions(bean, original)
            for (field in listOf(MtlsOptions.certificate, MtlsOptions.key)) {
                assertEquals("", ProtocolOptions.text(cleared, field))
                assertFalse(MtlsOptions.isSet(ProtocolOptions.text(cleared, field)))
            }
            assertEquals("retained-key", ProtocolOptions.get(cleared, "tls.reality.public_key")!!.asString)
            assertEquals(before, original.toString())
            val copy = bean.clone().apply { protocolOptionsJson = cleared.toString() }.clone()
            assertEquals("", ProtocolOptions.text(ProtocolOptions.parse(copy.protocolOptionsJson), MtlsOptions.key))
        }
    }

    @Test fun blankNormalizationNeverDropsNonBlankOrUnknownClientIdentityValues() {
        for(value in listOf("\"invalid synthetic input\"","42","{}","[\"\",\"invalid synthetic input\"]")) {
            val tls=root("""{"client_key":$value}""")
            val before=tls.deepCopy()
            MtlsOptions.omitBlankInline(tls)
            assertEquals(before,tls)
        }
    }


}
