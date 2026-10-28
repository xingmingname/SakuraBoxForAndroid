package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*

@Suppress("DEPRECATION")
class ChainSaveSafetyTest : InstrumentationTestCase() {
    private fun awaitMain(message: String, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync { ready = check() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail(message)
    }

    private fun exercise(cycleLength: Int, newProfile: Boolean = false) = runBlocking {
        assertTrue("Use a dedicated Debug test installation", BuildConfig.DEBUG)
        assertFalse("Use an idle dedicated test installation", DataStore.serviceState.canStop)
        val oldGroup = DataStore.selectedGroup
        val oldProxy = DataStore.selectedProxy
        val group = ProxyGroup(name = "instrumentation-chain-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        var activity: ChainSettingsActivity? = null
        try {
            DataStore.selectedGroup = group.id
            DataStore.selectedProxy = 0L
            fun add(bean: io.nekohasekai.sagernet.fmt.AbstractBean): ProxyEntity {
                val entity = ProxyEntity(groupId = group.id).putBean(bean)
                entity.id = SagerDatabase.proxyDao.addProxy(entity)
                return entity
            }
            val leaf = add(SOCKSBean().applyDefaultValues().apply {
                serverAddress = "127.0.0.1"; serverPort = 1080
            })
            val a = add(ChainBean().applyDefaultValues().apply { proxies = listOf(leaf.id) })
            val b = add(ChainBean().applyDefaultValues())
            val c = add(ChainBean().applyDefaultValues())
            fun link(node: ProxyEntity, children: List<Long>) {
                (node.requireBean() as ChainBean).proxies = children
                SagerDatabase.proxyDao.updateProxy(node)
            }
            link(b, if (cycleLength == 3 || newProfile) listOf(c.id, leaf.id) else listOf(a.id))
            link(c, if (newProfile) listOf(b.id) else if (cycleLength == 3) listOf(a.id) else listOf(leaf.id))
            val roots = listOf(b.id, leaf.id, b.id)
            fun launch(id: Long): ChainSettingsActivity {
                val intent = Intent(instrumentation.targetContext, ChainSettingsActivity::class.java)
                    .putExtra(ProfileSettingsActivity.EXTRA_PROFILE_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return (instrumentation.startActivitySync(intent) as ChainSettingsActivity).also { editor ->
                    awaitMain("Chain editor did not initialize") {
                        editor.supportFragmentManager.findFragmentById(R.id.settings) != null &&
                            (id == 0L || editor.proxyList.map { it.id } ==
                                (SagerDatabase.proxyDao.getById(id)!!.requireBean() as ChainBean).proxies)
                    }
                }
            }
            activity = launch(if (newProfile) 0 else a.id)
            val editor = activity!!
            instrumentation.runOnMainSync {
                editor.proxyList.clear()
                editor.proxyList.addAll(listOf(b, leaf, b))
                editor.configurationAdapter.notifyDataSetChanged()
                DataStore.dirty = true
            }
            withTimeout(10_000) { withContext(Dispatchers.Default) { editor.saveAndExit() } }
            instrumentation.waitForIdleSync()
            assertTrue(editor.isFinishing)
            val savedId = if (newProfile) SagerDatabase.proxyDao.getIdsByGroup(group.id)
                .single { it !in setOf(a.id, b.id, c.id, leaf.id) } else a.id
            val saved = SagerDatabase.proxyDao.getById(savedId)!!
            assertEquals(roots, (saved.requireBean() as ChainBean).proxies)
            activity = launch(savedId)
            assertEquals(roots, activity!!.proxyList.map { it.id })
            if (cycleLength > 0 || newProfile) {
                try { buildConfig(saved, forTest = true); fail("Recursive chain reached configuration output") }
                catch (e: IllegalArgumentException) {
                    assertEquals(activity!!.getString(R.string.circular_reference_sum), e.message)
                }
            }
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup = oldGroup
            DataStore.selectedProxy = oldProxy
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }

    fun testTwoChainsSaveAndFailGenerationSafely() = exercise(2)
    fun testThreeChainsSaveAndFailGenerationSafely() = exercise(3)
    fun testNewChainCanRetainPreexistingChildCycle() = exercise(2, newProfile = true)
    fun testSharedSubchainAndRepeatedOrdinaryNodesSave() = exercise(0)
    fun testRetiredPreferenceCannotRestoreTheCycleDialog() {
        val key = "configWarningDialogs"
        val previous = DataStore.configurationStore.getBoolean(key)
        try {
            DataStore.configurationStore.putBoolean(key, true)
            exercise(2)
        } finally {
            if (previous == null) DataStore.configurationStore.remove(key)
            else DataStore.configurationStore.putBoolean(key, previous)
        }
    }
}
