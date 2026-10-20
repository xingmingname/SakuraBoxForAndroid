package io.nekohasekai.sagernet.ui.profile

import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceManager
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean

@Suppress("DEPRECATION")
class ProtocolPreferenceRestoreTest : InstrumentationTestCase() {
    private class Store : PreferenceDataStore() {
        var writes = 0
        override fun getString(key: String, defValue: String?): String = "stale-per-field-value"
        override fun putString(key: String, value: String?) { writes++ }
    }

    fun testLegacyInitializationReallyErasesTheDisplay() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        val manager = PreferenceManager(context).apply { preferenceDataStore = Store() }
        val screen = manager.createPreferenceScreen(context)
        val edit = EditTextPreference(context).apply { key = "legacy-edit"; isPersistent = false; text = "30s" }
        val list = ListPreference(context).apply { key = "legacy-list"; isPersistent = false; value = "false" }
        screen.addPreference(edit); screen.addPreference(list)
        assertNull(edit.text); assertNull(list.value)
    }

    fun testSharedBindingSurvivesHierarchyAttachmentAndRecreation() = instrumentation.runOnMainSync {
        val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
        val store = Store()
        val manager = PreferenceManager(context).apply { preferenceDataStore = store }
        for (value in listOf("30s", "0", "false", "", "first\nsecond")) repeat(2) {
            val screen = manager.createPreferenceScreen(context)
            val edit = EditTextPreference(context).apply {
                key = "protocolAdvanced.test"; isPersistent = false; bindProtocolValue(value)
            }
            screen.addPreference(edit)
            assertEquals(value, edit.text)
            assertFalse(edit.isPersistent)
        }
        for (value in listOf("false", "true", "", "standard")) repeat(2) {
            val screen = manager.createPreferenceScreen(context)
            val list = ListPreference(context).apply {
                key = "protocolAdvanced.choice"; isPersistent = false
                entries = arrayOf("Core default", "false", "true", "standard")
                entryValues = arrayOf("", "false", "true", "standard")
                bindProtocolValue(value)
            }
            screen.addPreference(list)
            assertEquals(value, list.value)
            assertNotNull(list.entry)
        }
        assertEquals(0, store.writes)
    }

    fun testShadowTLSActualAndroidFormatterKeepsAdvancedValues() {
        val bean = ShadowTLSBean().apply {
            initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 443
            sni = "example.invalid"; password = "synthetic-test"
            protocolOptionsJson = """{"connect_timeout":"30s","tls":{"handshake_timeout":"5s","disable_sni":false}}"""
        }
        val reopened = KryoConverters.deserialize(ShadowTLSBean(), KryoConverters.serialize(bean))
        val outbound = buildSingBoxOutboundShadowTLSBean(reopened)
        ProtocolOptions.applyTo(outbound, reopened)
        val map = outbound.asMap()
        assertEquals("30s", map["connect_timeout"])
        val tls = map["tls"] as Map<*, *>
        assertEquals("5s", tls["handshake_timeout"]); assertEquals(false, tls["disable_sni"])
        assertEquals(bean.protocolOptionsJson, reopened.protocolOptionsJson)
    }
}
