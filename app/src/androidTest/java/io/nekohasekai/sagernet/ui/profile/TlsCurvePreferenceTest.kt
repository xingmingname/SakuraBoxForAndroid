package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity

@Suppress("DEPRECATION")
class TlsCurvePreferenceTest : InstrumentationTestCase() {
    fun testFingerprintAndRealityRefreshTheControlAndKeepCurveValues() {
        assertTrue(BuildConfig.DEBUG)
        assertFalse("Use an idle dedicated test installation", DataStore.serviceState.canStop)
        val previousGroup = DataStore.selectedGroup
        val group = ProxyGroup(name = "instrumentation-curves-" + System.nanoTime())
        group.id = SagerDatabase.groupDao.createGroup(group)
        var activity: AnyTLSSettingsActivity? = null
        try {
            DataStore.selectedGroup = group.id
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AnyTLSSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AnyTLSSettingsActivity
            val deadline = SystemClock.uptimeMillis() + 10_000
            var fragment: ProfileSettingsActivity.MyPreferenceFragmentCompat? = null
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                fragment = requireNotNull(activity).supportFragmentManager.findFragmentById(R.id.settings) as? ProfileSettingsActivity.MyPreferenceFragmentCompat
                if (fragment?.findPreference<Preference>("protocolAdvanced.tls.curve_preferences") != null) break
                SystemClock.sleep(20)
            }
            val editor = requireNotNull(fragment)
            fun change(key: String, value: String) {
                instrumentation.runOnMainSync {
                    val preference = editor.findPreference<ListPreference>(key)!!
                    assertTrue(preference.callChangeListener(value))
                    preference.value = value
                }
                instrumentation.waitForIdleSync()
            }
            fun check(enabled: Boolean) = instrumentation.runOnMainSync {
                val preference = editor.findPreference<EditTextPreference>("protocolAdvanced.tls.curve_preferences")!!
                assertEquals(enabled, preference.isEnabled)
                assertEquals("P256\nX25519", preference.text)
                assertEquals(listOf("P256", "X25519"), ProtocolOptions.get(
                    ProtocolOptions.parse(DataStore.profileCacheStore.getString(ProtocolAdvancedPreferences.CACHE_KEY)),
                    "tls.curve_preferences")!!.asJsonArray.map { it.asString })
                if (!enabled) assertEquals(instrumentation.targetContext.getString(R.string.protocol_tls_curves_standard_only), preference.summary)
            }
            change("utlsFingerprint", "")
            instrumentation.runOnMainSync {
                val preference = editor.findPreference<EditTextPreference>("protocolAdvanced.tls.curve_preferences")!!
                assertTrue(preference.isEnabled)
                assertTrue(preference.callChangeListener("P256\nX25519"))
                preference.text = "P256\nX25519"
            }
            instrumentation.waitForIdleSync()
            check(true)
            change("utlsFingerprint", "chrome")
            check(false)
            change("connectionSecurity", "reality")
            check(false)
            change("connectionSecurity", "tls")
            check(false)
            change("utlsFingerprint", "")
            check(true)
        } finally {
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup = previousGroup
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }
}
