package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class V2RayQuicPreferenceTest : InstrumentationTestCase() {
    private fun fragment(activity: StandardV2RaySettingsActivity) = activity.supportFragmentManager
        .findFragmentById(R.id.settings) as? ProfileSettingsActivity.MyPreferenceFragmentCompat

    private fun awaitEditor(activity: StandardV2RaySettingsActivity) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync { ready = fragment(activity)?.findPreference<Preference>("connectionSecurity") != null }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Editor did not initialize")
    }

    private fun exercise(protocol: String, invalid: String? = null) {
        assertTrue("Use the dedicated Debug test installation", BuildConfig.DEBUG)
        val oldGroup = DataStore.selectedGroup
        val oldProxy = DataStore.selectedProxy
        val group = ProxyGroup(name = "instrumentation-quic-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        var current: StandardV2RaySettingsActivity? = null
        val bean = (if (protocol == "trojan") TrojanBean().apply { password = "synthetic" }
            else VMessBean().apply { alterId = if (protocol == "vless") -1 else 0 }).apply {
            initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
            uuid = "00000000-0000-4000-8000-000000000001"; sni = "example.invalid"
            encryption = if (protocol == "vmess") "auto" else ""
            realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"; realityShortId = "00"
            ConnectionSecurity.select(this, ConnectionSecurity.Mode.TLS)
            type = if (invalid == null) "tcp" else "quic"
            when (invalid) {
                "none" -> ConnectionSecurity.select(this, ConnectionSecurity.Mode.NONE)
                "utls" -> utlsFingerprint = "chrome"
                "reality" -> ConnectionSecurity.select(this, ConnectionSecurity.Mode.REALITY)
            }
        }
        try {
            DataStore.selectedGroup = group.id
            val id = SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group.id).putBean(bean))
            fun open(): StandardV2RaySettingsActivity = (instrumentation.startActivitySync(
                SagerDatabase.proxyDao.getById(id)!!.settingIntent(instrumentation.targetContext, false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StandardV2RaySettingsActivity).also { awaitEditor(it) }
            current = open()
            if (invalid != null) {
                instrumentation.runOnMainSync {
                    val f = fragment(current!!)!!
                    assertEquals("quic", f.findPreference<ListPreference>("type")!!.value)
                    assertEquals(ConnectionSecurity.mode(bean).value, f.findPreference<ListPreference>("connectionSecurity")!!.value)
                }
                runBlocking { current!!.saveAndExit() }
                instrumentation.waitForIdleSync()
                assertTrue(current!!.isFinishing)
                assertEquals(bean, SagerDatabase.proxyDao.getById(id)!!.requireBean())
                return
            }
            instrumentation.runOnMainSync {
                val f = fragment(current!!)!!
                val transport = f.findPreference<ListPreference>("type")!!
                val picker = f.findPreference<ListPreference>("connectionSecurity")!!
                val fp = f.findPreference<ListPreference>("utlsFingerprint")!!
                assertTrue(transport.callChangeListener("quic")); transport.value = "quic"
                assertTrue(picker.callChangeListener("none")); picker.value="none"
                assertTrue(picker.callChangeListener("reality")); picker.value="reality"
                assertTrue(fp.callChangeListener("chrome")); fp.value="chrome"
                assertEquals("reality", picker.value); assertEquals("chrome", fp.value)
                assertTrue(transport.callChangeListener("ws")); transport.value = "ws"
                assertTrue(fp.callChangeListener("chrome")); fp.value = "chrome"
                assertTrue(picker.callChangeListener("reality")); picker.value = "reality"
                assertTrue(transport.callChangeListener("quic")); transport.value="quic"
                assertTrue(picker.callChangeListener("tls")); picker.value = "tls"
                assertTrue(fp.callChangeListener("")); fp.value = ""
                assertTrue(transport.callChangeListener("quic")); transport.value = "quic"
            }
            val monitor = instrumentation.addMonitor(current!!.javaClass.name, null, false)
            try {
                instrumentation.runOnMainSync { current!!.recreate() }
                current = monitor.waitForActivityWithTimeout(10_000) as? StandardV2RaySettingsActivity
                assertNotNull(current); awaitEditor(current!!)
            } finally { instrumentation.removeMonitor(monitor) }
            instrumentation.runOnMainSync {
                val f = fragment(current!!)!!
                assertEquals("quic", f.findPreference<ListPreference>("type")!!.value)
                assertEquals("tls", f.findPreference<ListPreference>("connectionSecurity")!!.value)
                assertTrue(f.findPreference<Preference>("packetEncoding")!!.isVisible == (protocol != "trojan"))
            }
            runBlocking { current!!.saveAndExit() }
            val saved = SagerDatabase.proxyDao.getById(id)!!.requireBean() as StandardV2RayBean
            assertEquals("quic", saved.type); assertEquals(bean.realityPubKey, saved.realityPubKey)
            current = open()
            runBlocking { current!!.saveAndExit() }
            assertEquals(saved, SagerDatabase.proxyDao.getById(id)!!.requireBean())
        } finally {
            current?.let { activity: Activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup = oldGroup; DataStore.selectedProxy = oldProxy
            SagerDatabase.proxyDao.deleteByGroup(group.id); SagerDatabase.groupDao.deleteGroup(group)
        }
    }

    fun testProtocolMenusAcceptUserChoicesAndSurviveRecreation() {
        for (protocol in listOf("vless", "vmess", "trojan")) exercise(protocol)
    }

    fun testLegacyInvalidNodesAreDisplayedWithoutAutomaticConversion() {
        for (protocol in listOf("vless", "vmess", "trojan")) for (mode in listOf("none", "utls", "reality"))
            exercise(protocol, mode)
    }

    fun testMixedSubscriptionAndQrParserKeepsInvalidAndValidNodes() = runBlocking {
        val nodes = parseProxies(listOf(
            "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?type=quic&security=none#legacy",
            "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?type=quic&security=tls&sni=example.invalid#valid",
            "trojan://synthetic@127.0.0.1:443?type=ws&security=tls#other"
        ).joinToString("\n"))
        assertEquals(3, nodes.size)
        assertEquals(ConnectionSecurity.Mode.NONE, ConnectionSecurity.mode(nodes[0]))
        assertEquals("tls",(nodes[1] as StandardV2RayBean).security)
    }
}
