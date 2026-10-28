package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.tuic.*
import moe.matsuri.nb4a.proxy.anytls.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.io.File
import com.google.gson.Gson

class ForcedResolveSniTest {
    private val updater = object : GroupUpdater() {
        override suspend fun doUpdate(proxyGroup: ProxyGroup, subscription: SubscriptionBean, userInterface: GroupManager.Interface?, byUser: Boolean) {}
        fun resolve(bean: AbstractBean) = rewriteAddress(bean, listOf(InetAddress.getByAddress(byteArrayOf(127,0,0,1))), false)
    }
    private fun beans(): List<AbstractBean> = listOf(AnyTLSBean(), TuicBean()).onEach {
        it.initializeDefaultValues(); it.serverAddress = "origin.invalid"; it.serverPort = 443
        if (it is TuicBean) { it.uuid = "00000000-0000-4000-8000-000000000001"; it.token = "synthetic" }
        if (it is AnyTLSBean) it.password = "synthetic"
    }
    private fun sni(bean: AbstractBean, value: String) { when(bean) { is AnyTLSBean -> bean.sni=value; is TuicBean -> bean.sni=value } }
    private fun generated(bean: AbstractBean): Map<String, Any?> {
        val out = when(bean) { is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean); is TuicBean -> buildSingBoxOutboundTuicBean(bean, false); else -> error("type") }
        ProtocolOptions.applyTo(out, bean)
        return out.asMap()
    }
    private fun tls(bean: AbstractBean) = generated(bean)["tls"] as Map<*, *>
    private fun fixture(bean: AbstractBean, suffix: String = "") {
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { dir ->
            File(dir, "sc005").mkdirs()
            File(dir, "sc005/${bean.javaClass.simpleName}$suffix.json").writeText(Gson().toJson(mapOf("outbounds" to listOf(generated(bean)))))
        }
    }
    @Test fun implicitSniSurvivesForcedResolutionAndSerialization() {
        for (bean in beans()) {
            updater.resolve(bean)
            assertEquals("127.0.0.1", bean.serverAddress)
            assertEquals("origin.invalid", tls(bean)["server_name"])
            assertEquals(tls(bean), tls(bean.clone()))
            fixture(bean)
        }
    }
    @Test fun explicitBeanAndAdvancedSniAreNotOverwritten() {
        for (bean in beans()) {
            sni(bean, "explicit.invalid"); updater.resolve(bean)
            assertEquals("explicit.invalid", tls(bean)["server_name"])
            bean.serverAddress = "origin.invalid"
            bean.protocolOptionsJson = """{"tls":{"server_name":"advanced.invalid"}}"""
            updater.resolve(bean); assertEquals("advanced.invalid", tls(bean)["server_name"])
        }
    }
    @Test fun explicitDisableSniIsPreservedAndAnExplicitFalseOverrideRemainsEffective() {
        for (bean in beans()) {
            bean.protocolOptionsJson = """{"tls":{"disable_sni":true}}"""
            updater.resolve(bean); assertTrue(tls(bean)["disable_sni"] == true)
            assertEquals("origin.invalid", tls(bean)["server_name"])
            fixture(bean, "-disabled-sni")
        }
        val bean = beans().filterIsInstance<TuicBean>().single().apply { disableSNI = true }
        updater.resolve(bean); assertEquals("origin.invalid", bean.sni); assertTrue(tls(bean)["disable_sni"] == true)
        bean.serverAddress = "origin.invalid"; bean.protocolOptionsJson = """{"tls":{"disable_sni":false}}"""
        updater.resolve(bean); assertEquals("origin.invalid", tls(bean)["server_name"])
    }
    @Test fun emptyAdvancedServerNameRetainsItsImplicitHostnameMeaning() {
        for (bean in beans()) {
            bean.protocolOptionsJson = """{"tls":{"server_name":"","alpn":["h3"]}}"""
            updater.resolve(bean)
            assertEquals("origin.invalid", tls(bean)["server_name"])
            assertTrue(bean.protocolOptionsJson.contains("alpn"))
        }
    }
    @Test fun realityRetainsHostnameWithoutEnablingDisabledCertificateSettings() {
        val bean = beans().filterIsInstance<AnyTLSBean>().single()
        bean.protocolOptionsJson = """{"tls":{"reality":{"enabled":true,"public_key":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc","short_id":""},"disable_sni":true}}"""
        updater.resolve(bean)
        assertEquals("origin.invalid", tls(bean)["server_name"])
        assertFalse(tls(bean).containsKey("disable_sni"))
        assertTrue(bean.protocolOptionsJson.contains("disable_sni"))
    }
    @Test fun ipAndUnresolvedClonesKeepIndependentTlsState() {
        for (bean in beans()) {
            val resolved = bean.clone(); updater.resolve(resolved)
            assertEquals("origin.invalid", generated(bean)["server"])
            assertTrue((tls(bean)["server_name"] as? String).isNullOrBlank())
            assertEquals("origin.invalid", tls(resolved)["server_name"])
            bean.serverAddress = "192.0.2.1"; updater.resolve(bean)
            assertTrue((tls(bean)["server_name"] as? String).isNullOrBlank())
        }
    }
    @Test fun malformedAdvancedOptionsDoNotExposeTheirContentsOrReplaceTheDomain() {
        for (bean in beans()) {
            bean.protocolOptionsJson = """{"tls":"SYNTHETIC_PRIVATE_VALUE"}"""
            val error = assertThrows(IllegalArgumentException::class.java) { updater.resolve(bean) }
            assertFalse(error.message!!.contains("SYNTHETIC_PRIVATE_VALUE"))
            assertEquals("origin.invalid", bean.serverAddress)
        }
    }
}
