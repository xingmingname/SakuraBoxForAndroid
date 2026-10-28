package io.nekohasekai.sagernet.ui.profile

import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import androidx.preference.PreferenceManager
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore

@Suppress("DEPRECATION")
class ValidationFeedbackInstrumentedTest : InstrumentationTestCase() {
    fun testRetiredStoredSettingCannotRecreateTheSettingsEntry() {
        assertTrue("Use a dedicated Debug test installation", BuildConfig.DEBUG)
        assertFalse(DataStore.serviceState.canStop)
        val key = "configWarningDialogs"
        val previous = DataStore.configurationStore.getBoolean(key)
        try {
            for (stored in listOf(true, false)) {
                DataStore.configurationStore.putBoolean(key, stored)
                instrumentation.runOnMainSync {
                    val context = ContextThemeWrapper(instrumentation.targetContext, androidx.appcompat.R.style.Theme_AppCompat)
                    val manager = PreferenceManager(context).apply { preferenceDataStore = DataStore.configurationStore }
                    val screen = manager.inflateFromResource(context, R.xml.global_preferences, null)
                    assertNull(screen.findPreference<androidx.preference.Preference>(key))
                }
            }
        } finally {
            if (previous == null) DataStore.configurationStore.remove(key)
            else DataStore.configurationStore.putBoolean(key, previous)
        }
    }

    fun testAnyTlsSaveAndJsonExitIgnoreRetiredPreference() {
        assertTrue(BuildConfig.DEBUG)
        assertFalse(DataStore.serviceState.canStop)
        val key = "configWarningDialogs"
        val previous = DataStore.configurationStore.getBoolean(key)
        try {
            for (stored in listOf(true, false)) {
                DataStore.configurationStore.putBoolean(key, stored)
                EditorExitInstrumentedTest().also { it.injectInstrumentation(instrumentation) }
                    .testMalformedJsonFailureAllowsBothExitRoutesWithoutModalWarning()
                EditorExitInstrumentedTest().also { it.injectInstrumentation(instrumentation) }
                    .testMalformedAnyTlsMtlsCanSaveAndReopenWithoutWarning()
            }
        } finally {
            if (previous == null) DataStore.configurationStore.remove(key)
            else DataStore.configurationStore.putBoolean(key, previous)
        }
    }
}
