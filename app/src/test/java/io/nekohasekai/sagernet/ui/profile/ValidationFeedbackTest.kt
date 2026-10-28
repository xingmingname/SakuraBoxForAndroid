package io.nekohasekai.sagernet.ui.profile

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ValidationFeedbackTest {
    private val root = generateSequence(File(".").canonicalFile) { it.parentFile }
        .first { File(it, "app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt").isFile }
    private fun source(name: String) = File(root, "app/src/main/java/io/nekohasekai/sagernet/$name.kt").readText()
    private fun nodes()=listOf<AbstractBean>(AnyTLSBean(),VMessBean().apply { alterId=-1 },VMessBean(),TrojanBean(),HttpBean(),ShadowTLSBean(),
        HysteriaBean().apply { protocolVersion=1 },HysteriaBean().apply { protocolVersion=2 },TuicBean().apply { protocolVersion=5 }).onEach {
        it.initializeDefaultValues();it.serverAddress="example.invalid";it.serverPort=443
        if(it is StandardV2RayBean){it.security="tls";it.uuid="00000000-0000-0000-0000-000000000001"}
        if(it is HysteriaBean){it.protocol=HysteriaBean.PROTOCOL_UDP;it.serverPorts="443"}
    }

    @Test fun malformedAndPartialMtlsCanBeSerializedAcrossNativeProtocols() {
        for(bean in nodes())for(fields in listOf("\"client_certificate\":\"invalid synthetic PEM\"","\"client_key\":\"invalid synthetic PEM\"",
            "\"client_certificate\":\"invalid synthetic PEM\",\"client_key\":\"invalid synthetic PEM\"")) {
            bean.protocolOptionsJson="""{"tls":{$fields}}"""
            val before=KryoConverters.serialize(bean)
            val tls=ProtocolOptions.validationOutbound(bean,ProtocolOptions.parse(bean.protocolOptionsJson))!!.getAsJsonObject("tls")
            assertTrue(tls.has("client_certificate") || tls.has("client_key"))
            assertArrayEquals(before,KryoConverters.serialize(bean))
        }
    }
    @Test fun syntaxAndObjectGuardsStillProtectExistingJson() {
        for(value in listOf("{broken","[]","42"))assertThrows(Exception::class.java){ProtocolOptions.parse(value)}
        val original=ProtocolOptions.parse("""{"tls":"retained-scalar"}""")
        assertThrows(IllegalArgumentException::class.java){ProtocolOptions.set(original,MtlsOptions.key,"synthetic")}
        assertEquals("retained-scalar",original["tls"].asString)
    }
    @Test fun ordinaryTlsWithoutIdentityKeepsItsExistingDefaults() {
        for(bean in nodes()) {
            val out=ProtocolOptions.validationOutbound(bean,JsonObject())!!
            assertNull(ProtocolOptions.get(out,"tls.client_key"))
            assertNull(ProtocolOptions.get(out,"tls.client_certificate"))
            assertFalse(ProtocolOptions.get(out,"tls.insecure")?.asBoolean==true)
        }
    }
    @Test fun invalidPinTextDoesNotChangeItsStoredValue() {
        val field=ProtocolOptions.Field("tls.certificate_public_key_sha256",ProtocolOptions.Kind.PINS)
        for(value in listOf("not-base64","AQI=","synthetic")) {
            val options=ProtocolOptions.set(JsonObject(),field,value)
            assertEquals(value,ProtocolOptions.text(options,field))
            val bean=nodes().first().apply{protocolOptionsJson=options.toString()}
            assertEquals(value,ProtocolOptions.text(ProtocolOptions.parse(bean.clone().protocolOptionsJson),field))
        }
    }
    @Test fun retiredFeatureHasNoProductionCodeOrResourceEntry() {
        val forbidden=listOf("ConfigWarningDialogs","CONFIG_WARNING_DIALOGS","configWarningDialogs","config_warning_","chain_warning_",
            "TlsValidation","QuicTransportException","QuicECHConfigException","mtls_error_","tls_error_","connection_quic_transport_required","connection_reality_invalid","protocol_options_conflict","protocol_quic_static_ech_required")
        File(root,"app/src/main").walkTopDown().filter{it.isFile && it.extension in setOf("kt","java","xml")}.forEach{file->
            val text=file.readText();for(token in forbidden)assertFalse("${file.relativeTo(root)} $token",text.contains(token))
        }
    }
    @Test fun ordinarySaveOnlyParsesJsonBeforePersistence() {
        val profile=source("ui/profile/ProfileSettingsActivity")
        assertTrue(profile.contains("ProtocolOptions.parse(edited.protocolOptionsJson)"))
        assertFalse(profile.contains("ProtocolOptions.validate"));assertFalse(profile.contains("MtlsOptions.validate"))
        assertTrue(profile.indexOf("ProtocolOptions.parse")<profile.indexOf("ProfileManager.createProfile"))
        assertTrue(profile.contains("currentCoroutineContext().ensureActive()"))
    }
    @Test fun runtimeCoreAndReferenceProtectionsStayIndependentOfRemovedWarnings() {
        assertTrue(source("fmt/ConfigBuilder").contains("chainGraph.within"))
        assertTrue(source("fmt/ConfigBuilder").contains("requireAndroidJsonVpn"))
        assertTrue(source("fmt/NodeJsonProfile").contains("Libcore.newSingBoxInstance"))
        assertFalse(source("ui/profile/ChainSettingsActivity").contains("override suspend fun saveAndExit"))
    }
    @Test fun jsonDiscardAndCommitProtectionsRemain() {
        val json=source("ui/profile/ConfigEditActivity")
        assertTrue(json.contains("if (nodeModel.committing) return"))
        assertTrue(json.contains("if (nodeModel.discard()) finish()"))
        assertEquals(2,Regex("beforeCommit\\(\\)").findAll(source("fmt/NodeJsonProfile")).count())
        assertTrue(source("ui/profile/NodeJsonEditorModel").contains("saveGuard.beginCommit()"))
    }
}
