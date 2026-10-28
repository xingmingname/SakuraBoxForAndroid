package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.preference.Preference
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("DEPRECATION")
class EditorExitInstrumentedTest : InstrumentationTestCase() {
    private fun awaitMain(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            SystemClock.sleep(20)
        }
        fail("Editor state did not settle")
    }

    private fun prompt(editor: ProfileSettingsActivity<*>): AlertDialog? =
        (editor.supportFragmentManager.fragments.singleOrNull {
            it is ProfileSettingsActivity.UnsavedChangesDialogFragment
        } as? ProfileSettingsActivity.UnsavedChangesDialogFragment)?.dialog as? AlertDialog

    private fun exercise(kind: String, toolbar: Boolean, invalid: Boolean) = runBlocking {
        assertTrue("Use a dedicated Debug test installation", BuildConfig.DEBUG)
        assertFalse("Never run against an active VPN", DataStore.serviceState.canStop)
        val oldGroup = DataStore.selectedGroup
        val oldProxy = DataStore.selectedProxy
        val group = ProxyGroup(name = "instrumentation-exit-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        var editor: ProfileSettingsActivity<*>? = null
        try {
            DataStore.selectedGroup = group.id
            DataStore.selectedProxy = 0
            val bean: AbstractBean = when (kind) {
                "anytls" -> AnyTLSBean()
                "trojan" -> TrojanBean()
                "vless" -> VMessBean().apply { alterId = -1 }
                "vmess" -> VMessBean().apply { alterId = 0 }
                else -> SOCKSBean()
            }
            bean.initializeDefaultValues()
            bean.name = "original synthetic node"
            bean.serverAddress = "127.0.0.1"; bean.serverPort = 443
            val entity = ProxyEntity(groupId = group.id).putBean(bean)
            entity.id = SagerDatabase.proxyDao.addProxy(entity)
            val original = SagerDatabase.proxyDao.getById(entity.id)!!.requireBean().clone()
            val host = instrumentation.startActivitySync(entity.settingIntent(instrumentation.targetContext, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProfileSettingsActivity<*>
            editor = host
            awaitMain { host.supportFragmentManager.findFragmentById(R.id.settings)?.view != null }
            instrumentation.runOnMainSync {
                DataStore.profileName = "unsaved synthetic name"
                if (invalid) DataStore.profileCacheStore.putString(ProtocolAdvancedPreferences.CACHE_KEY,
                    "{broken")
                DataStore.dirty = true
            }
            if (invalid) {
                withContext(Dispatchers.Default) { host.saveAndExit() }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    assertFalse(host.isFinishing)
                    assertTrue("A failed save must not open a modal warning", host.window.decorView.hasWindowFocus())
                    assertFalse(host.supportFragmentManager.fragments.any {
                        it is androidx.fragment.app.DialogFragment && it.dialog?.isShowing == true
                    })
                }
            }
            assertEquals(original, SagerDatabase.proxyDao.getById(entity.id)!!.requireBean())
            instrumentation.runOnMainSync {
                if (toolbar) {
                    val item = PopupMenu(host, host.findViewById(R.id.toolbar)).menu.add(0, android.R.id.home, 0, "")
                    assertTrue(host.onOptionsItemSelected(item))
                } else host.onBackPressedDispatcher.onBackPressed()
                assertNotNull(prompt(host))
                host.onBackPressedDispatcher.onBackPressed()
                assertEquals(1, host.supportFragmentManager.fragments.count { it is ProfileSettingsActivity.UnsavedChangesDialogFragment })
                prompt(host)!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }
            awaitMain { prompt(host) == null }
            instrumentation.runOnMainSync {
                assertFalse(host.isFinishing)
                assertEquals("unsaved synthetic name", DataStore.profileName)
                host.onBackPressedDispatcher.onBackPressed()
                prompt(host)!!.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            awaitMain { host.isFinishing || host.isDestroyed }
            assertEquals(original, SagerDatabase.proxyDao.getById(entity.id)!!.requireBean())
        } finally {
            editor?.let { instrumentation.runOnMainSync { if (!it.isFinishing) it.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup = oldGroup; DataStore.selectedProxy = oldProxy
            SagerDatabase.proxyDao.deleteByGroup(group.id); SagerDatabase.groupDao.deleteGroup(group)
        }
    }

    fun testMalformedJsonFailureAllowsBothExitRoutesWithoutModalWarning() {
        for (toolbar in listOf(false, true)) exercise("anytls", toolbar, true)
    }

    fun testMalformedAnyTlsMtlsCanSaveAndReopenWithoutWarning() = runBlocking {
        assertTrue(BuildConfig.DEBUG)
        assertFalse(DataStore.serviceState.canStop)
        val oldGroup=DataStore.selectedGroup;val oldProxy=DataStore.selectedProxy
        val group=ProxyGroup(name="instrumentation-mtls-save-${System.nanoTime()}")
        group.id=SagerDatabase.groupDao.createGroup(group)
        var editor: ProfileSettingsActivity<*>?=null
        try {
            DataStore.selectedGroup=group.id;DataStore.selectedProxy=0
            val bean=AnyTLSBean().apply{initializeDefaultValues();serverAddress="127.0.0.1";serverPort=443}
            val entity=ProxyEntity(groupId=group.id).putBean(bean)
            entity.id=SagerDatabase.proxyDao.addProxy(entity)
            fun open()=(instrumentation.startActivitySync(entity.settingIntent(instrumentation.targetContext,false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProfileSettingsActivity<*>).also{host->
                awaitMain{host.supportFragmentManager.findFragmentById(R.id.settings)?.view!=null}
            }
            editor=open()
            val options="""{"tls":{"client_certificate":"invalid synthetic PEM","client_key":"invalid synthetic PEM"}}"""
            instrumentation.runOnMainSync{DataStore.profileCacheStore.putString(ProtocolAdvancedPreferences.CACHE_KEY,options);DataStore.dirty=true}
            withContext(Dispatchers.Default){editor!!.saveAndExit()}
            awaitMain{editor!!.isFinishing || editor!!.isDestroyed}
            val stored=SagerDatabase.proxyDao.getById(entity.id)!!.requireBean()
            assertEquals(options,stored.protocolOptionsJson)
            editor=open()
            assertEquals(options,DataStore.profileCacheStore.getString(ProtocolAdvancedPreferences.CACHE_KEY))
            instrumentation.runOnMainSync{editor!!.onBackPressedDispatcher.onBackPressed()}
            awaitMain{editor!!.isFinishing || editor!!.isDestroyed}
            assertEquals(options,SagerDatabase.proxyDao.getById(entity.id)!!.requireBean().protocolOptionsJson)
        } finally {
            editor?.let{instrumentation.runOnMainSync{it.finish()}}
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup=oldGroup;DataStore.selectedProxy=oldProxy
            SagerDatabase.proxyDao.deleteByGroup(group.id);SagerDatabase.groupDao.deleteGroup(group)
        }
    }

    fun testHardFailureStillAllowsBackAndDiscard() = exercise("anytls", false, true)
    fun testToolbarDiscardAfterFailureKeepsStoredNode() = exercise("anytls", true, true)
    fun testDifferentProtocolEditorsCanDiscardThroughBothNavigationPaths() {
        for (kind in listOf("vless", "vmess", "trojan", "socks"))
            for (toolbar in listOf(false, true)) exercise(kind, toolbar, false)
    }

    fun testLegacyJsonValidationFailureCanBeDiscardedWithoutSaving() {
        assertTrue(BuildConfig.DEBUG)
        assertFalse(DataStore.serviceState.canStop)
        val key = "instrumentation-exit-json-${System.nanoTime()}"
        DataStore.profileCacheStore.putString(key, "{}")
        var editor: ConfigEditActivity? = null
        try {
            val host = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ConfigEditActivity::class.java)
                .putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConfigEditActivity
            editor = host
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                host.binding.editor.setTextContent("{broken")
                host.saveAndExit()
            }
            instrumentation.waitForIdleSync()
            assertEquals("{}", DataStore.profileCacheStore.getString(key))
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                host.onBackPressedDispatcher.onBackPressed()
                val fragment = host.supportFragmentManager.fragments.single { it is ConfigEditActivity.UnsavedChangesDialogFragment }
                    as ConfigEditActivity.UnsavedChangesDialogFragment
                (fragment.dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            awaitMain { host.isFinishing || host.isDestroyed }
            assertEquals("{}", DataStore.profileCacheStore.getString(key))
        } finally {
            editor?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.profileCacheStore.remove(key)
        }
    }
}
