package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.SwitchPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity

@Suppress("DEPRECATION")
class ConnectionSecurityPreferenceTest : InstrumentationTestCase() {

    fun testAnyTlsEchSwitchSavesAndReopensWithPem() = runBlocking {
        assertTrue(BuildConfig.DEBUG)
        val group = ProxyGroup(name = "instrumentation-anytls-ech-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        val pem = "-----BEGIN ECH CONFIGS-----\nsynthetic\n-----END ECH CONFIGS-----"
        val bean = AnyTLSBean().apply {
            initializeDefaultValues()
            serverAddress = "example.invalid"
            serverPort = 443
            echConfig = pem
        }
        val id = SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group.id).putBean(bean))
        var activity: AnyTLSSettingsActivity? = null
        fun open(): AnyTLSSettingsActivity {
            val intent = Intent(instrumentation.targetContext, AnyTLSSettingsActivity::class.java)
                .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val editor = instrumentation.startActivitySync(intent) as AnyTLSSettingsActivity
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                instrumentation.runOnMainSync {
                    val fragment = editor.supportFragmentManager.findFragmentById(R.id.settings) as? ProfileSettingsActivity.MyPreferenceFragmentCompat
                    ready = fragment?.findPreference<SwitchPreference>("enableECH")?.onPreferenceChangeListener != null
                }
                if (ready) return editor
                SystemClock.sleep(20)
            }
            error("AnyTLS ECH editor did not initialize")
        }
        try {
            val editor = open()
            activity = editor
            instrumentation.runOnMainSync {
                val toggle = editor.child.findPreference<SwitchPreference>("enableECH")!!
                assertTrue(toggle.isChecked)
                assertTrue(toggle.callChangeListener(false))
                toggle.isChecked = false
                val security = editor.child.findPreference<ListPreference>("connectionSecurity")!!
                assertTrue(security.callChangeListener("reality"))
                assertFalse(toggle.isVisible)
                assertTrue(security.callChangeListener("tls"))
                assertTrue(toggle.isVisible)
                assertFalse(toggle.isChecked)
            }
            withContext(Dispatchers.Default) { editor.saveAndExit() }
            instrumentation.waitForIdleSync()
            assertTrue(editor.isFinishing)
            val saved = SagerDatabase.proxyDao.getById(id)!!.requireBean() as AnyTLSBean
            assertEquals(pem, saved.echConfig)
            assertEquals(false, ProtocolOptions.get(ProtocolOptions.parse(saved.protocolOptionsJson), "tls.ech.enabled")!!.asBoolean)

            val reopened = open()
            activity = reopened
            instrumentation.runOnMainSync {
                assertFalse(reopened.child.findPreference<SwitchPreference>("enableECH")!!.isChecked)
                assertEquals(pem, reopened.child.findPreference<EditTextPreference>("echConfig")!!.text)
            }
        } finally {
            activity?.let { editor -> instrumentation.runOnMainSync { editor.finish() } }
            instrumentation.waitForIdleSync()
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }

    fun testSecurityMenusCanInflateBeforeDynamicModeBinding() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        for (xml in listOf(R.xml.standard_v2ray_preferences, R.xml.anytls_preferences)) {
            val manager = PreferenceManager(context).apply { preferenceDataStore = object : PreferenceDataStore() {} }
            val screen = manager.inflateFromResource(context, xml, null)
            val picker = screen.findPreference<ListPreference>("connectionSecurity")!!
            assertEquals(3, picker.entryValues.size)
            assertEquals(3, picker.entries.size)
        }
    }

    fun testModeBindingSurvivesAndroidXHierarchyAttachment() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        val store = object : PreferenceDataStore() {
            override fun getString(key: String, defValue: String?): String = "stale-mode"
            override fun putString(key: String, value: String?) { fail("Rendering must not write per-field state") }
        }
        val manager = PreferenceManager(context).apply { preferenceDataStore = store }
        for (mode in ConnectionSecurity.Mode.entries) repeat(2) {
            val screen = manager.createPreferenceScreen(context)
            val picker = ListPreference(context).apply {
                key = "connectionSecurity"; isPersistent = false
                entries = arrayOf("None", "TLS", "REALITY")
                entryValues = arrayOf("none", "tls", "reality")
                bindProtocolValue(mode.value)
            }
            screen.addPreference(picker)
            assertEquals(mode.value, picker.value)
            assertNotNull(picker.entry)
        }
    }

    fun testModeAndInactiveParametersSurviveTheAndroidBeanSerializer() {
        val bean = VMessBean().apply {
            initializeDefaultValues(); alterId = -1; security = "tls"
            realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
            realityShortId = "00"; certificates = "synthetic-inactive-cert"
        }
        for (mode in ConnectionSecurity.Mode.entries) {
            ConnectionSecurity.select(bean, mode)
            val copy = bean.clone() as VMessBean
            assertEquals(mode, ConnectionSecurity.mode(copy))
            assertEquals(bean.realityPubKey, copy.realityPubKey)
            assertEquals(bean.certificates, copy.certificates)
        }
    }

    fun testFullVlessImportEntryAndShareLinkRoundTripOnAndroid() {
        val key = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
        val link = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?security=reality&pbk=$key&sid=00&sni=example.invalid&fp=firefox"
        val bean = parseV2Ray(link).apply { initializeDefaultValues() }
        assertEquals(ConnectionSecurity.Mode.REALITY, ConnectionSecurity.mode(bean))
        assertEquals(key, bean.realityPubKey)
        assertEquals("firefox", bean.utlsFingerprint)
        val again = parseV2Ray(bean.toUriVMessVLESSTrojan(false)).apply { initializeDefaultValues() }
        assertEquals(bean.sni, again.sni)
        assertEquals(bean.realityShortId, again.realityShortId)
        assertEquals(key, again.realityPubKey)
    }
}
