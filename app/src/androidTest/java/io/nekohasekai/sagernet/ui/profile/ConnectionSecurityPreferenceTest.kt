package io.nekohasekai.sagernet.ui.profile

import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan

@Suppress("DEPRECATION")
class ConnectionSecurityPreferenceTest : InstrumentationTestCase() {
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
