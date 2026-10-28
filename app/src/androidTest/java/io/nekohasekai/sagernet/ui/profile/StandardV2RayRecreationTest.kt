package io.nekohasekai.sagernet.ui.profile

import android.app.Activity
import android.content.Intent
import android.test.InstrumentationTestCase
import android.os.SystemClock
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class StandardV2RayRecreationTest : InstrumentationTestCase() {
    private fun node(kind: String): StandardV2RayBean = (if (kind == "trojan") TrojanBean().apply {
        password = "synthetic-original"
    } else VMessBean().apply { alterId = if (kind == "vless") -1 else 0 }).apply {
        initializeDefaultValues()
        serverAddress = "127.0.0.1"; serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
        type = "ws"; host = "stored.invalid"; path = "/stored"
        sni = "stored.invalid"; packetEncoding = 1
        realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
        realityShortId = "00"
    }

    private fun fragment(activity: StandardV2RaySettingsActivity) = activity.supportFragmentManager
        .findFragmentById(R.id.settings) as? ProfileSettingsActivity.MyPreferenceFragmentCompat

    private fun awaitEditor(activity: StandardV2RaySettingsActivity) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync { ready = fragment(activity)?.findPreference<Preference>("connectionSecurity") != null }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Editor did not initialize")
    }

    private fun recreated(activity: StandardV2RaySettingsActivity): StandardV2RaySettingsActivity {
        val monitor = instrumentation.addMonitor(activity.javaClass.name, null, false)
        try {
            instrumentation.runOnMainSync { activity.recreate() }
            val next = monitor.waitForActivityWithTimeout(10_000) as? StandardV2RaySettingsActivity
            assertNotNull("Activity recreation did not complete", next)
            assertNotSame(activity, next)
            return next!!.also { awaitEditor(it) }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun assertControls(activity: StandardV2RaySettingsActivity, kind: String) {
        instrumentation.runOnMainSync {
            val f = fragment(activity)!!
            val vmess = kind == "vmess"
            val vless = kind == "vless"
            assertEquals(vmess || vless, f.findPreference<Preference>("encryption")!!.isVisible)
            assertEquals(vmess || vless, f.findPreference<Preference>("packetEncoding")!!.isVisible)
            assertEquals(vmess, f.findPreference<Preference>("alterId")!!.isVisible)
            assertFalse(f.findPreference<Preference>("username")!!.isVisible)
            assertFalse(f.findPreference<Preference>("password")!!.isVisible)
            assertEquals(instrumentation.targetContext.getString(if (kind == "trojan") R.string.password else R.string.uuid),
                f.findPreference<Preference>("uuid")!!.title)
        }
    }

    private fun exercise(kind: String, existing: Boolean, mode: ConnectionSecurity.Mode) {
        assertTrue("Use the dedicated Debug test installation", BuildConfig.DEBUG)
        val oldGroup = DataStore.selectedGroup
        val oldProxy = DataStore.selectedProxy
        val group = ProxyGroup(name = "instrumentation-recreation-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        var current: StandardV2RaySettingsActivity? = null
        try {
            DataStore.selectedGroup = group.id
            val input = node(kind)
            ConnectionSecurity.select(input, mode)
            val id = if (existing) SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group.id).putBean(input)) else 0L
            val intent = if (existing) SagerDatabase.proxyDao.getById(id)!!.settingIntent(instrumentation.targetContext, false)
            else Intent(instrumentation.targetContext,
                if (kind == "trojan") TrojanSettingsActivity::class.java else VMessSettingsActivity::class.java)
                .putExtra("vless", kind == "vless")
            current = instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StandardV2RaySettingsActivity
            awaitEditor(current!!)
            assertControls(current!!, kind)
            instrumentation.runOnMainSync {
                val f = fragment(current!!)!!
                if (!existing) {
                    for ((key, value) in listOf("realityPubKey" to input.realityPubKey, "realityShortId" to input.realityShortId)) {
                        val field = f.findPreference<EditTextPreference>(key)!!
                        assertTrue(field.callChangeListener(value))
                        field.text = value
                    }
                    val picker = f.findPreference<ListPreference>("connectionSecurity")!!
                    assertTrue(picker.callChangeListener(mode.value))
                    picker.value = mode.value
                }
                val transport = f.findPreference<ListPreference>("type")!!
                assertTrue(transport.callChangeListener("ws"))
                transport.value = "ws"
                f.findPreference<EditTextPreference>("sni")!!.text = "unsaved.invalid"
                f.findPreference<EditTextPreference>("host")!!.text = "unsaved-host.invalid"
                f.findPreference<EditTextPreference>("path")!!.text = "/unsaved"
                if (kind != "trojan") f.findPreference<ListPreference>("packetEncoding")!!.value = "2"
                if (kind == "vmess") {
                    f.findPreference<ListPreference>("encryption")!!.value = "aes-128-gcm"
                    f.findPreference<EditTextPreference>("alterId")!!.text = "7"
                }
                if (kind == "trojan") f.findPreference<EditTextPreference>("uuid")!!.text = "synthetic-unsaved"
            }
            instrumentation.waitForIdleSync()
            current = recreated(current!!)
            assertControls(current!!, kind)
            instrumentation.runOnMainSync {
                val f = fragment(current!!)!!
                assertEquals(mode.value, f.findPreference<ListPreference>("connectionSecurity")!!.value)
                assertEquals("unsaved.invalid", f.findPreference<EditTextPreference>("sni")!!.text)
                assertEquals("/unsaved", f.findPreference<EditTextPreference>("path")!!.text)
                if (kind != "trojan") assertEquals("2", f.findPreference<ListPreference>("packetEncoding")!!.value)
                if (kind == "vmess") {
                    assertEquals("aes-128-gcm", f.findPreference<ListPreference>("encryption")!!.value)
                    assertEquals("7", f.findPreference<EditTextPreference>("alterId")!!.text)
                }
                if (kind == "trojan") assertEquals("synthetic-unsaved", f.findPreference<EditTextPreference>("uuid")!!.text)
            }
            runBlocking { current!!.saveAndExit() }
            instrumentation.waitForIdleSync()
            val savedId = if (existing) id else SagerDatabase.proxyDao.getIdsByGroup(group.id).single()
            fun saved() = SagerDatabase.proxyDao.getById(savedId)!!.requireBean() as StandardV2RayBean
            val first = saved()
            assertEquals("unsaved.invalid", first.sni)
            assertEquals("unsaved-host.invalid", first.host)
            assertEquals("/unsaved", first.path)
            assertEquals(mode, ConnectionSecurity.mode(first))
            if (kind != "trojan") assertEquals(2, first.packetEncoding.toInt())
            if (kind == "vmess") assertEquals("aes-128-gcm", first.encryption)
            if (kind == "trojan") assertEquals("synthetic-unsaved", (first as TrojanBean).password)
            current = instrumentation.startActivitySync(SagerDatabase.proxyDao.getById(savedId)!!
                .settingIntent(instrumentation.targetContext, false).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as StandardV2RaySettingsActivity
            awaitEditor(current!!)
            assertControls(current!!, kind)
            runBlocking { current!!.saveAndExit() }
            assertEquals(first, saved())
        } finally {
            current?.let { activity: Activity -> instrumentation.runOnMainSync { activity.finish() } }
            instrumentation.waitForIdleSync()
            DataStore.selectedGroup = oldGroup
            DataStore.selectedProxy = oldProxy
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }

    fun testVlessNewAndExistingEditorsSurviveRecreation() {
        for (existing in listOf(false, true)) for (mode in ConnectionSecurity.Mode.entries) exercise("vless", existing, mode)
    }

    fun testVmessNewAndExistingEditorsSurviveRecreation() {
        for (existing in listOf(false, true)) for (mode in ConnectionSecurity.Mode.entries) exercise("vmess", existing, mode)
    }

    fun testTrojanNewAndExistingEditorsKeepPasswordBinding() {
        for (existing in listOf(false, true)) for (mode in ConnectionSecurity.Mode.entries) exercise("trojan", existing, mode)
    }

    fun testPacketEncodingAliasesUseTheCompleteImportEntry() = runBlocking {
        for ((alias, expected) in listOf("packet" to 1, "packetaddr" to 1, "xudp" to 2, "none" to 0)) {
            val node = parseProxies("vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?packetEncoding=$alias").single() as VMessBean
            assertEquals(expected, node.packetEncoding.toInt())
        }
    }
}
