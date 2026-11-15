package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
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
import kotlinx.coroutines.runBlocking
import libcore.Libcore
import moe.matsuri.nb4a.ui.SimpleMenuPreference

@Suppress("DEPRECATION")
class SudokuSettingsTest : InstrumentationTestCase() {
    private fun assertStableUpdates(activity: SudokuSettingsActivity) {
        lateinit var screen: PreferenceScreen
        lateinit var rows: Map<String, Preference>
        lateinit var groups: Map<SudokuPreferenceLayout.Section, PreferenceCategory>
        var resets = 0
        var inserted = 0
        var removed = 0
        lateinit var adapter: RecyclerView.Adapter<*>
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { resets++ }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { inserted += itemCount }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { removed += itemCount }
        }
        instrumentation.runOnMainSync {
            screen = activity.child.preferenceScreen
            rows = SudokuOptions.fields.associate { it.path to activity.child.findPreference<Preference>("sudoku.${it.path}")!! }
            groups = SudokuPreferenceLayout.Section.entries.associateWith { activity.child.findPreference<PreferenceCategory>("sudoku.group.$it")!! }
            assertEquals(0, groups.getValue(SudokuPreferenceLayout.Section.ADVANCED).initialExpandedChildrenCount)
            groups.getValue(SudokuPreferenceLayout.Section.ADVANCED).initialExpandedChildrenCount = Int.MAX_VALUE
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            adapter = activity.child.listView.adapter!!
            assertTrue(adapter.hasStableIds())
            adapter.registerAdapterDataObserver(observer)
            val position = (0 until adapter.itemCount).first {
                (adapter as PreferenceGroupAdapter).getItem(it)?.key == "sudoku.padding_min"
            }
            (activity.child.listView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, 13)
            activity.child.listView.requestFocus()
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        var anchorKey: String? = null
        var anchorTop = 0
        var focus: android.view.View? = null
        instrumentation.runOnMainSync {
            val layout = activity.child.listView.layoutManager as LinearLayoutManager
            val first = layout.findFirstVisibleItemPosition()
            anchorKey = (adapter as PreferenceGroupAdapter).getItem(first)?.key
            anchorTop = layout.findViewByPosition(first)!!.top
            focus = activity.currentFocus
        }
        try {
            for ((path, value) in listOf("aead" to "none", "ascii" to "prefer_ascii", "table_limit" to "512",
                "padding_min" to "1", "aead" to "aes-128-gcm", "ascii" to "prefer_entropy", "table_limit" to "0",
                "httpmask.mode" to "ws", "httpmask.host" to "mask.invalid", "httpmask.multiplex" to "auto")) {
                instrumentation.runOnMainSync { assertTrue(rows.getValue(path).callChangeListener(value)) }
                instrumentation.waitForIdleSync(); SystemClock.sleep(100)
                instrumentation.runOnMainSync {
                    assertSame(screen, activity.child.preferenceScreen)
                    rows.forEach { (path, row) -> assertSame(row, activity.child.findPreference<Preference>("sudoku.$path")) }
                    groups.forEach { (group, row) -> assertSame(row, activity.child.findPreference<PreferenceCategory>("sudoku.group.$group")) }
                    val layout = activity.child.listView.layoutManager as LinearLayoutManager
                    val first = layout.findFirstVisibleItemPosition()
                    assertEquals(anchorKey, (adapter as PreferenceGroupAdapter).getItem(first)?.key)
                    assertEquals(anchorTop, layout.findViewByPosition(first)!!.top)
                    assertSame(focus, activity.currentFocus)
                    assertEquals(0, resets); assertEquals(0, inserted); assertEquals(0, removed)
                }
            }
            instrumentation.runOnMainSync { assertTrue(rows.getValue("enable_pure_downlink").callChangeListener(false)) }
            instrumentation.waitForIdleSync(); SystemClock.sleep(100)
            instrumentation.runOnMainSync {
                assertEquals(0, resets)
                assertEquals(1, inserted)
                assertEquals(0, removed)
                assertSame(rows.getValue("enable_pure_downlink"), activity.child.findPreference<Preference>("sudoku.enable_pure_downlink"))
            }
        } finally {
            instrumentation.runOnMainSync { adapter.unregisterAdapterDataObserver(observer) }
        }
    }

    private fun awaitEditor(activity: SudokuSettingsActivity) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync {
                ready = (activity.supportFragmentManager.findFragmentById(io.nekohasekai.sagernet.R.id.settings)
                    as? androidx.preference.PreferenceFragmentCompat)?.findPreference<EditTextPreference>("sudoku.key") != null
            }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Sudoku editor was not ready")
    }

    fun testCreateSaveReloadCopyJsonModeAndDeleteThroughExistingNodeFlow() = runBlocking {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("Use the separate local Sudoku test installation", "app.sakurabox.client.sudoku.debug", BuildConfig.APPLICATION_ID)
        assertFalse(DataStore.serviceState.canStop)
        assertTrue(Libcore.versionBox().lineSequence().last().split(',').contains("with_sudoku"))
        val previousGroup = DataStore.selectedGroup
        val previousSelected = DataStore.selectedProxy
        val group = SagerDatabase.groupDao.createGroup(ProxyGroup(name = "Sudoku instrumentation ${System.nanoTime()}", type = GroupType.BASIC))
        val activities = mutableListOf<SudokuSettingsActivity>()
        try {
            DataStore.selectedGroup = group
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SudokuSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SudokuSettingsActivity
            activities += activity
            awaitEditor(activity)
            instrumentation.runOnMainSync {
                val limit = activity.child.findPreference<SimpleMenuPreference>("sudoku.table_limit")!!
                val aead = activity.child.findPreference<SimpleMenuPreference>("sudoku.aead")!!
                assertEquals(aead.javaClass, limit.javaClass)
                assertEquals(listOf("32","64","128","256","512","0"),limit.entryValues.map { it.toString() })
                assertEquals("32",limit.value)
                assertEquals("32",limit.summary.toString())
                assertEquals("",aead.summary.toString())
                assertEquals("",activity.child.findPreference<EditTextPreference>("sudoku.padding_min")!!.summary.toString())
                assertEquals("",activity.child.findPreference<SwitchPreference>("sudoku.httpmask.disable")!!.summary.toString())
            }
            assertStableUpdates(activity)
            for (value in listOf("64","128","256","512","0","32","64")) {
                instrumentation.runOnMainSync {
                    assertTrue(activity.child.findPreference<SimpleMenuPreference>("sudoku.table_limit")!!.callChangeListener(value))
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val limit = activity.child.findPreference<SimpleMenuPreference>("sudoku.table_limit")!!
                    assertEquals(value,limit.value)
                    assertEquals(if (value == "0") activity.getString(io.nekohasekai.sagernet.R.string.sudoku_table_limit_unlimited) else value,limit.summary.toString())
                }
            }
            instrumentation.runOnMainSync {
                assertTrue(activity.child.findPreference<SimpleMenuPreference>("sudoku.aead")!!.callChangeListener("none"))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val aead = activity.child.findPreference<SimpleMenuPreference>("sudoku.aead")!!
                assertEquals("none",aead.value)
                assertEquals("none",aead.summary.toString())
            }
            instrumentation.runOnMainSync {
                activity.child.findPreference<EditTextPreference>("name")!!.text = "Sudoku native UI"
                activity.child.findPreference<EditTextPreference>("serverAddress")!!.text = "127.0.0.1"
                activity.child.findPreference<EditTextPreference>("serverPort")!!.text = "443"
                assertTrue(activity.child.findPreference<EditTextPreference>("sudoku.key")!!.callChangeListener("synthetic-ui-key"))
                assertTrue(activity.child.findPreference<EditTextPreference>("sudoku.padding_min")!!.callChangeListener("0"))
                assertTrue(activity.child.findPreference<SwitchPreference>("sudoku.enable_pure_downlink")!!.callChangeListener(false))
            }
            activity.saveAndExit()
            instrumentation.waitForIdleSync()
            val saved = SagerDatabase.proxyDao.getByGroup(group).single()
            assertEquals(ProxyEntity.TYPE_SUDOKU, saved.type)
            val bean = saved.requireBean() as SudokuBean
            assertEquals(64,SudokuOptions.root(bean)["table_limit"].asInt)
            assertEquals("none",SudokuOptions.root(bean)["aead"].asString)
            assertEquals("synthetic-ui-key", SudokuOptions.root(bean)["key"].asString)
            assertEquals(0, SudokuOptions.root(bean)["padding_min"].asInt)
            assertFalse(SudokuOptions.root(bean)["enable_pure_downlink"].asBoolean)
            DataStore.selectedProxy = saved.id
            val generated = buildConfig(saved, forTest = true)
            assertTrue(generated.config.contains("sudoku"))
            Libcore.checkConfig(generated.config)
            Libcore.newSingBoxInstance(generated.config, moe.matsuri.nb4a.net.LocalResolverImpl).close()
            val reopened = instrumentation.startActivitySync(saved.settingIntent(instrumentation.targetContext, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SudokuSettingsActivity
            activities += reopened
            awaitEditor(reopened)
            instrumentation.runOnMainSync {
                assertEquals("64",reopened.child.findPreference<SimpleMenuPreference>("sudoku.table_limit")!!.value)
                assertEquals("none",reopened.child.findPreference<SimpleMenuPreference>("sudoku.aead")!!.summary.toString())
                assertEquals("synthetic-ui-key", reopened.child.findPreference<EditTextPreference>("sudoku.key")!!.text)
                assertEquals("0", reopened.child.findPreference<EditTextPreference>("sudoku.padding_min")!!.text)
                assertFalse(reopened.child.findPreference<SwitchPreference>("sudoku.enable_pure_downlink")!!.isChecked)
            }
            val json = """{"type":"sudoku","server":"127.0.0.1","server_port":8443,"key":"json-ui-key","httpmask":{"disable":true}}"""
            instrumentation.runOnMainSync {
                assertFalse(reopened.child.findPreference<Preference>("sudoku.jsonPriority")!!.isVisible)
                instrumentation.callActivityOnPause(reopened)
            }
            NodeJsonProfile.save(saved.id, NodeJson.state(bean), NodeJson.OUTBOUND, json)
            instrumentation.runOnMainSync { instrumentation.callActivityOnResume(reopened) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(reopened.child.findPreference<Preference>("sudoku.jsonPriority")!!.isVisible)
                assertEquals("synthetic-ui-key", reopened.child.findPreference<EditTextPreference>("sudoku.key")!!.text)
            }
            instrumentation.runOnMainSync { reopened.child.findPreference<EditTextPreference>("sudoku.key")!!.callChangeListener("ordinary-edited-key") }
            reopened.saveAndExit()
            val latest = SagerDatabase.proxyDao.getById(saved.id)!!
            assertEquals(json, latest.requireBean().customOutboundJson)
            assertEquals(NodeJson.OUTBOUND, latest.requireBean().jsonMode)
            val copy = ProfileManager.createProfile(group, latest.requireBean().clone())
            assertTrue(copy.id != saved.id)
            assertEquals(latest.requireBean().protocolOptionsJson, copy.requireBean().protocolOptionsJson)
            assertEquals(json, copy.requireBean().customOutboundJson)
            ProfileManager.deleteProfile(group, copy.id)
            assertNull(SagerDatabase.proxyDao.getById(copy.id))
        } finally {
            instrumentation.runOnMainSync { activities.forEach { if (!it.isFinishing) it.finish() } }
            SagerDatabase.proxyDao.getByGroup(group).forEach { ProfileManager.deleteProfile(group, it.id) }
            SagerDatabase.groupDao.deleteById(group)
            DataStore.selectedGroup = previousGroup; DataStore.selectedProxy = previousSelected
        }
    }
}
