package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import org.junit.Assert.*
import org.junit.Test

class TlsCurvePreferencesTest {
    private fun nodes(): List<AbstractBean> = listOf(
        AnyTLSBean(), VMessBean().apply { alterId = -1 }, VMessBean(), TrojanBean(), HttpBean(), ShadowTLSBean()
    ).onEach {
        it.initializeDefaultValues(); it.serverAddress = "example.invalid"; it.serverPort = 443
        if (it is StandardV2RayBean) { it.security = "tls"; it.utlsFingerprint = "" }
        if (it is AnyTLSBean) it.utlsFingerprint = ""
    }
    private fun curves() = ProtocolOptions.parse("""{"tls":{"curve_preferences":["P256","X25519"]}}""")
    private fun fingerprint(bean: AbstractBean, value: String) {
        when (bean) {
            is StandardV2RayBean -> bean.utlsFingerprint = value
            is AnyTLSBean -> bean.utlsFingerprint = value
        }
    }

    @Test fun nativeStandardTlsKeepsItsCurveSupport() {
        for (bean in nodes()) {
            assertTrue(ProtocolOptions.supportsTlsCurvePreferences(bean, curves()))
            assertEquals(listOf("P256", "X25519"), ProtocolOptions.validationOutbound(bean, curves())!!
                .getAsJsonObject("tls").getAsJsonArray("curve_preferences").map { it.asString })
        }
    }

    @Test fun fingerprintAndRealityModesDisableTheControlWithoutErasingStoredCurves() {
        for (bean in nodes().filter { it !is ShadowTLSBean }) {
            bean.protocolOptionsJson = curves().toString()
            fingerprint(bean, "chrome")
            assertFalse(ProtocolOptions.supportsTlsCurvePreferences(bean, curves()))
            fingerprint(bean, "")
            assertTrue(ProtocolOptions.supportsTlsCurvePreferences(bean, curves()))
            if (ConnectionSecurity.Mode.REALITY in ConnectionSecurity.supported(bean)) {
                ConnectionSecurity.select(bean, ConnectionSecurity.Mode.REALITY)
                val stored = ProtocolOptions.parse(bean.protocolOptionsJson)
                assertFalse(ProtocolOptions.supportsTlsCurvePreferences(bean, stored))
                assertEquals(listOf("P256", "X25519"), ProtocolOptions.validationOutbound(bean, stored)!!
                    .getAsJsonObject("tls").getAsJsonArray("curve_preferences").map { it.asString })
                ConnectionSecurity.select(bean, ConnectionSecurity.Mode.TLS)
                assertTrue(ProtocolOptions.supportsTlsCurvePreferences(bean, ProtocolOptions.parse(bean.protocolOptionsJson)))
            }
            assertEquals(ProtocolOptions.get(curves(), "tls.curve_preferences"),
                ProtocolOptions.get(ProtocolOptions.parse(bean.clone().protocolOptionsJson), "tls.curve_preferences"))
        }
    }

    @Test fun decisionUsesFinalOverridesAndDoesNotChangeExplicitJson() {
        val bean = nodes().first()
        fingerprint(bean, "chrome")
        val options = ProtocolOptions.parse("""{"tls":{"utls":{"enabled":false},"curve_preferences":["P256"]}}""")
        assertTrue(ProtocolOptions.supportsTlsCurvePreferences(bean, options))
        bean.jsonMode = NodeJson.LEGACY
        bean.customOutboundJson = """{"tls":{"utls":{"enabled":true,"fingerprint":"firefox"}}}"""
        assertFalse(ProtocolOptions.supportsTlsCurvePreferences(bean, options))
        bean.customOutboundJson = """{"tls":{"utls":{"enabled":false}}}"""
        assertTrue(ProtocolOptions.supportsTlsCurvePreferences(bean, options))
        options.getAsJsonObject("tls").addProperty("engine", "windows")
        assertFalse(ProtocolOptions.supportsTlsCurvePreferences(bean, options))
        val raw = """{"type":"http","server":"example.invalid","server_port":443,"tls":{"enabled":true,"utls":{"enabled":true,"fingerprint":"chrome"},"curve_preferences":["P256"]}}"""
        NodeJson.set(bean, NodeJson.OUTBOUND, raw)
        assertEquals(raw, bean.customOutboundJson)
        assertEquals(raw, bean.clone().customOutboundJson)
    }

    @Test fun noneHidesTlsAndRestoringTlsRecoversTheCurveValues() {
        val bean = nodes()[1] as VMessBean
        bean.protocolOptionsJson = curves().toString()
        ConnectionSecurity.select(bean, ConnectionSecurity.Mode.NONE)
        assertFalse(ProtocolOptions.supportsTlsCurvePreferences(bean, curves()))
        assertNull(ProtocolOptions.validationOutbound(bean, curves())!!.get("tls"))
        ConnectionSecurity.select(bean, ConnectionSecurity.Mode.TLS)
        assertTrue(ProtocolOptions.supportsTlsCurvePreferences(bean, curves()))
        assertEquals(curves().toString(), bean.protocolOptionsJson)
    }
}
