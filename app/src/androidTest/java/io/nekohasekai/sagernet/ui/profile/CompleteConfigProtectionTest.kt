package io.nekohasekai.sagernet.ui.profile

import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.config.ConfigBean

@Suppress("DEPRECATION")
class CompleteConfigProtectionTest : InstrumentationTestCase() {
    fun testBothCompleteEntrypointsCheckTheMigratedRuntimeConfiguration() {
        assertTrue(BuildConfig.DEBUG)
        assertFalse("Use an idle dedicated test installation", DataStore.serviceState.canStop)
        val previousMode = DataStore.serviceMode
        val raw = """{"inbounds":[{"type":"tun","inet4_address":["172.19.0.1/30"],"auto_route":true}],"outbounds":[{"type":"direct","tag":"proxy"}],"route":{"final":"proxy"}}"""
        try {
            DataStore.serviceMode = Key.MODE_VPN
            for (legacy in listOf(false, true)) {
                fun entity(json: String) = ProxyEntity().apply {
                    putBean(if (legacy) ConfigBean().applyDefaultValues().apply { type = 0; config = json }
                    else VMessBean().applyDefaultValues().apply { NodeJson.set(this, NodeJson.FULL, json) })
                }
                val invalid = entity(raw)
                try { buildConfig(invalid); fail("Unprotected runtime configuration was accepted") }
                catch (e: NodeJson.Invalid) { assertEquals(NodeJson.ErrorKind.ENVIRONMENT, e.kind) }
                val protected = NodeJson.parse(raw).apply {
                    getAsJsonObject("route").addProperty("auto_detect_interface", true)
                }.toString()
                val valid = entity(protected)
                val before = KryoConverters.serialize(valid.requireBean())
                val built = NodeJson.parse(buildConfig(valid).config)
                assertTrue(built.getAsJsonObject("route")["auto_detect_interface"].asBoolean)
                assertTrue(built.getAsJsonArray("inbounds")[0].asJsonObject.has("address"))
                assertEquals(before.toList(), KryoConverters.serialize(valid.requireBean()).toList())
            }
            DataStore.serviceMode = Key.MODE_PROXY
            val proxyOnly = ProxyEntity().apply {
                putBean(ConfigBean().applyDefaultValues().apply {
                    type = 0; config = """{"outbounds":[{"type":"direct","tag":"proxy"}]}"""
                })
            }
            assertTrue(NodeJson.parse(buildConfig(proxyOnly).config).has("outbounds"))
        } finally { DataStore.serviceMode = previousMode }
    }
}
