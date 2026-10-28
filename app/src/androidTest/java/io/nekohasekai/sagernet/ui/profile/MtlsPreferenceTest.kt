package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import android.widget.EditText
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceCategory
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity

@Suppress("DEPRECATION")
class MtlsPreferenceTest : InstrumentationTestCase() {
    fun testTwoPreferencesAndSafeSummariesInBothThemes() = instrumentation.runOnMainSync {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val base = instrumentation.targetContext
            val configuration = Configuration(base.resources.configuration).apply {
                uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or night
            }
            val context = ContextThemeWrapper(base.createConfigurationContext(configuration), androidx.appcompat.R.style.Theme_AppCompat)
            val manager = PreferenceManager(context).apply { preferenceDataStore = object : PreferenceDataStore() {
                override fun getString(key: String, defValue: String?) = "stale-private-text"
                override fun putString(key: String, value: String?) { fail("Rendering must not persist PEM outside the shared editor state") }
            } }
            for (xml in listOf(R.xml.anytls_preferences, R.xml.standard_v2ray_preferences)) {
                val screen = manager.inflateFromResource(context, xml, null)
                for (key in listOf("mtlsClientCertificate", "mtlsClientKey")) {
                    val pref = screen.findPreference<EditTextPreference>(key)!!
                    assertFalse(pref.isPersistent)
                    for (value in listOf("", " \n\t", "-----BEGIN PRIVATE KEY-----\nsynthetic\n-----END PRIVATE KEY-----\n", "")) {
                        pref.bindMtlsValue(value)
                        assertEquals(value, pref.text)
                        assertEquals(context.getString(if (value.isBlank()) R.string.mtls_unset else R.string.mtls_set), pref.summary)
                        assertFalse(pref.summary.toString().contains("synthetic"))
                    }
                }
            }
        }
    }

    fun testUnsavedAnyTlsPemSurvivesActivityRecreation() {
        assertTrue("Use a dedicated Debug installation", BuildConfig.DEBUG)
        val oldGroup = DataStore.selectedGroup
        val group = ProxyGroup(name = "instrumentation-mtls-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        var current: AnyTLSSettingsActivity? = null
        fun await(activity: AnyTLSSettingsActivity): ProfileSettingsActivity.MyPreferenceFragmentCompat {
            val deadline = SystemClock.uptimeMillis() + 10_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                val f = activity.supportFragmentManager.findFragmentById(R.id.settings) as? ProfileSettingsActivity.MyPreferenceFragmentCompat
                if (f?.findPreference<EditTextPreference>("mtlsClientKey") != null) return f
                SystemClock.sleep(20)
            }
            error("mTLS editor did not initialize")
        }
        try {
            DataStore.selectedGroup = group.id
            current = instrumentation.startActivitySync(Intent(instrumentation.targetContext, AnyTLSSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AnyTLSSettingsActivity
            val fragment = await(current!!)
            val values = mapOf("mtlsClientCertificate" to "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n",
                "mtlsClientKey" to "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n")
            instrumentation.runOnMainSync {
                for ((name, value) in values) {
                    val pref = fragment.findPreference<EditTextPreference>(name)!!
                    assertTrue(pref.callChangeListener(value)); pref.text = value
                    assertEquals(instrumentation.targetContext.getString(R.string.mtls_set), pref.summary)
                }
            }
            val monitor = instrumentation.addMonitor(AnyTLSSettingsActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { current!!.recreate() }
                current = monitor.waitForActivityWithTimeout(10_000) as? AnyTLSSettingsActivity
                assertNotNull(current)
            } finally { instrumentation.removeMonitor(monitor) }
            val restored = await(current!!)
            instrumentation.runOnMainSync {
                for ((name, value) in values) assertEquals(value, restored.findPreference<EditTextPreference>(name)!!.text)
                val key = restored.findPreference<EditTextPreference>("mtlsClientKey")!!
                assertTrue(key.callChangeListener("")); key.text = ""
                assertEquals(instrumentation.targetContext.getString(R.string.mtls_unset), key.summary)
                for ((name, value) in values) {
                    val pref = restored.findPreference<EditTextPreference>(name)!!
                    assertTrue(pref.callChangeListener(value)); pref.text = value
                }
                val category = restored.findPreference<PreferenceCategory>("protocolAdvancedCategory")!!
                val clear = (0 until category.preferenceCount).map(category::getPreference)
                    .first { it.title == instrumentation.targetContext.getString(R.string.protocol_clear_options) }
                assertTrue(clear.onPreferenceClickListener!!.onPreferenceClick(clear))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                for (name in values.keys) {
                    val pref = restored.findPreference<EditTextPreference>(name)!!
                    assertEquals("", pref.text)
                    assertEquals(instrumentation.targetContext.getString(R.string.mtls_unset), pref.summary)
                }
                val pref = restored.findPreference<EditTextPreference>("mtlsClientKey")!!
                restored.onDisplayPreferenceDialog(pref)
                current!!.supportFragmentManager.executePendingTransactions()
                val dialog = current!!.supportFragmentManager.fragments.filterIsInstance<DialogFragment>().single()
                val input = dialog.requireDialog().findViewById<EditText>(android.R.id.edit)!!
                input.setText("unconfirmed PEM draft")
                restored.syncMtlsValues("")
                assertEquals("unconfirmed PEM draft", input.text.toString())
                dialog.dismissNow()
                assertEquals("", pref.text)
                assertTrue(DataStore.profileCacheStore.getString(ProtocolAdvancedPreferences.CACHE_KEY).isNullOrBlank())
            }
        } finally {
            current?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup = oldGroup
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }
}
