package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.util.Base64
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.NodeJsonProfile
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.fmt.sudoku.SudokuShareLink
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import libcore.Libcore

/** Device-only coverage of the shared clipboard/scanner parser and existing editor/Room flow. */
@Suppress("DEPRECATION")
class SudokuShareImportTest : InstrumentationTestCase() {
    fun testShareImportSaveEditCopyJsonAndDelete() = runBlocking {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("app.sakurabox.client.sudoku.debug", BuildConfig.APPLICATION_ID)
        assertFalse(DataStore.serviceState.canStop)
        assertTrue(Libcore.versionBox().contains("sudoku: "))
        val previousGroup = DataStore.selectedGroup
        val previousSelected = DataStore.selectedProxy
        val group = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "Sudoku share test ${System.nanoTime()}", type = GroupType.BASIC))
        var activity: SudokuSettingsActivity? = null
        try {
            DataStore.selectedGroup = group
            val payload = """{"h":"127.0.0.1","p":443,"k":"synthetic-key","n":"中文 + /?#%","e":"chacha20-poly1305","m":10233,"hd":true,"hx":"on"}"""
            val link = "sudoku://" + Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val bean = RawUpdater.parseRaw(link)!!.single() as SudokuBean
            val saved = ProfileManager.createProfile(group, bean)
            assertEquals(ProxyEntity.TYPE_SUDOKU, saved.type)
            assertEquals(10233, (saved.requireBean() as SudokuBean).shareLinkLocalPort)
            val reopened = instrumentation.startActivitySync(saved.settingIntent(instrumentation.targetContext, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SudokuSettingsActivity
            activity = reopened
            val deadline = SystemClock.uptimeMillis() + 10000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    ready = (reopened.supportFragmentManager.findFragmentById(R.id.settings) as? androidx.preference.PreferenceFragmentCompat)
                        ?.findPreference<EditTextPreference>("sudoku.key") != null
                }
                if (!ready) SystemClock.sleep(20)
            }
            assertTrue(ready)
            instrumentation.runOnMainSync {
                assertEquals("中文 + /?#%", reopened.child.findPreference<EditTextPreference>("name")!!.text)
                assertEquals("5", reopened.child.findPreference<EditTextPreference>("sudoku.padding_min")!!.text)
                assertTrue(reopened.child.findPreference<EditTextPreference>("sudoku.key")!!.callChangeListener("  Exact+%2B Key  "))
            }
            reopened.saveAndExit()
            instrumentation.waitForIdleSync()
            val latest = SagerDatabase.proxyDao.getById(saved.id)!!
            val stored = latest.requireBean() as SudokuBean
            assertEquals(10233, stored.shareLinkLocalPort)
            assertEquals("  Exact+%2B Key  ", SudokuOptions.root(stored)["key"].asString)
            assertEquals(SudokuOptions.root(stored), SudokuOptions.root(SudokuShareLink.parse(latest.toStdLink())))
            DataStore.selectedProxy = latest.id
            val config = buildConfig(latest, forTest = true)
            Libcore.checkConfig(config.config)
            Libcore.newSingBoxInstance(config.config, moe.matsuri.nb4a.net.LocalResolverImpl).close()
            val copy = ProfileManager.createProfile(group, stored.clone())
            assertEquals(latest.toStdLink(), copy.toStdLink())
            val json = """{"type":"sudoku","server":"127.0.0.1","server_port":8443,"key":"json-key","padding_min":0}"""
            NodeJsonProfile.save(copy.id, NodeJson.state(copy.requireBean()), NodeJson.OUTBOUND, json)
            val jsonNode = SagerDatabase.proxyDao.getById(copy.id)!!
            assertFalse(jsonNode.haveStandardLink()); assertTrue(jsonNode.toStdLink().startsWith("sn://sudoku?"))
            assertEquals(json, jsonNode.requireBean().customOutboundJson)
            ProfileManager.deleteProfile(group, copy.id)
            assertNull(SagerDatabase.proxyDao.getById(copy.id))
        } finally {
            instrumentation.runOnMainSync { activity?.let { if (!it.isFinishing) it.finish() } }
            SagerDatabase.proxyDao.getByGroup(group).forEach { ProfileManager.deleteProfile(group, it.id) }
            SagerDatabase.groupDao.deleteById(group)
            DataStore.selectedGroup = previousGroup; DataStore.selectedProxy = previousSelected
        }
    }
}
