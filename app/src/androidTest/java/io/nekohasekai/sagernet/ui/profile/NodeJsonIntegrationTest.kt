package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.SystemClock
import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import kotlinx.coroutines.runBlocking

@Suppress("DEPRECATION")
class NodeJsonIntegrationTest : InstrumentationTestCase() {
    private fun node() = VMessBean().apply {
        initializeDefaultValues(); alterId = -1; serverAddress = "127.0.0.1"; serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
    }
    private fun isolated(run: (ProxyEntity) -> Unit) {
        assertTrue("Use the dedicated Debug installation", BuildConfig.DEBUG)
        assertFalse("Do not change an active user's VPN", DataStore.serviceState.canStop)
        val group = ProxyGroup(name = "node-json-instrumentation-${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        try {
            val profile = ProxyEntity(groupId = group.id).putBean(node())
            profile.id = SagerDatabase.proxyDao.addProxy(profile)
            run(profile)
        } finally {
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }
    fun testRealGeneratorDatabaseTakeoverDisableAndAtomicFailure() = isolated { original -> runBlocking {
        val before = KryoConverters.serialize(original.requireBean())
        val preview = NodeJsonProfile.preview(original, NodeJson.OUTBOUND)
        assertTrue(preview.text.isNotBlank())
        assertEquals(before.toList(), KryoConverters.serialize(SagerDatabase.proxyDao.getById(original.id)!!.requireBean()).toList())
        val replacement = NodeJson.parse(preview.text).apply { addProperty("server_port", 8443) }.toString()
        NodeJsonProfile.save(original.id, NodeJson.state(original.requireBean()), NodeJson.OUTBOUND, replacement)
        val active = SagerDatabase.proxyDao.getById(original.id)!!
        assertEquals(443, active.requireBean().serverPort.toInt())
        val config = NodeJson.parse(buildConfig(active, forPreview = true).config)
        assertEquals(8443, NodeJson.previewOutbound(config)["server_port"].asInt)
        val saved = NodeJson.state(active.requireBean())
        SagerDatabase.proxyDao.updateTraffic(original.id, 12, 34)
        SagerDatabase.proxyDao.updateTestResult(original.id, 1, 25, null)
        runBlocking { ProfileManager.updateProfile(original) }
        assertEquals(saved, NodeJson.state(SagerDatabase.proxyDao.getById(original.id)!!.requireBean()))
        try {
            NodeJsonProfile.save(original.id, saved, NodeJson.FULL, "{invalid}")
            fail("Invalid JSON was saved")
        } catch (_: NodeJson.Invalid) { }
        assertEquals(saved, NodeJson.state(SagerDatabase.proxyDao.getById(original.id)!!.requireBean()))
        NodeJsonProfile.save(original.id, saved, NodeJson.NONE, "")
        val disabled = SagerDatabase.proxyDao.getById(original.id)!!
        assertEquals(replacement, disabled.requireBean().customOutboundJson)
        assertEquals(443, NodeJson.previewOutbound(NodeJson.parse(buildConfig(disabled, forPreview = true).config))["server_port"].asInt)
    } }
    fun testCompleteJsonIsNotMergedWithGlobalSettings() = isolated { profile -> runBlocking {
        val preview = NodeJsonProfile.preview(profile, NodeJson.FULL)
        val root = NodeJson.parse(preview.text)
        root.getAsJsonArray("inbounds")?.forEach { if (it.asJsonObject["type"].asString == "tun") it.asJsonObject.addProperty("stack", "system") }
        NodeJsonProfile.save(profile.id, NodeJson.state(profile.requireBean()), NodeJson.FULL, root.toString())
        val stored = SagerDatabase.proxyDao.getById(profile.id)!!
        val built = NodeJson.parse(buildConfig(stored, forPreview = true).config)
        assertEquals(root["inbounds"], built["inbounds"])
        assertEquals(root["dns"], built["dns"]); assertEquals(root["route"], built["route"])
        assertEquals(443, stored.requireBean().serverPort.toInt())
    } }
    fun testSelectorValidatesCandidateAndIsolatesBrokenInactiveJson() = isolated { profile ->
        val group = SagerDatabase.groupDao.getById(profile.groupId)!!
        group.isSelector = true; SagerDatabase.groupDao.updateGroup(group)
        val broken = ProxyEntity(groupId = group.id).putBean(node().apply {
            NodeJson.set(this, NodeJson.OUTBOUND, """{"type":"future-unknown-protocol","tag":"broken"}""")
        })
        broken.id = SagerDatabase.proxyDao.addProxy(broken)
        val config = buildConfig(profile, forPreview = true)
        assertTrue(config.profileTagMap.containsKey(profile.id))
        assertFalse(config.profileTagMap.containsKey(broken.id))
        val before = NodeJson.state(profile.requireBean())
        try {
            NodeJsonProfile.validate(profile, NodeJson.OUTBOUND, """{"type":"http","server":"127.0.0.1","server_port":443,"unknown_field":true}""")
            fail("The selector validated its database copy instead of the candidate")
        } catch (_: NodeJson.Invalid) { }
        assertEquals(before, NodeJson.state(SagerDatabase.proxyDao.getById(profile.id)!!.requireBean()))
    }
    fun testFullTakeoverCannotInvalidateExistingChainReferences() = isolated { profile -> runBlocking {
        val snapshot = NodeJsonProfile.preview(profile, NodeJson.FULL).text
        val chain = ProxyEntity(groupId = profile.groupId).putBean(io.nekohasekai.sagernet.fmt.internal.ChainBean().apply {
            initializeDefaultValues(); proxies = listOf(profile.id)
        })
        chain.id = SagerDatabase.proxyDao.addProxy(chain)
        val before = NodeJson.state(profile.requireBean())
        try {
            NodeJsonProfile.save(profile.id, before, NodeJson.FULL, snapshot)
            fail("A shared outbound was converted into a complete configuration")
        } catch (e: NodeJson.Invalid) { assertEquals(NodeJson.ErrorKind.REFERENCE, e.kind) }
        assertEquals(before, NodeJson.state(SagerDatabase.proxyDao.getById(profile.id)!!.requireBean()))
    } }
    private fun awaitEditor(activity: ConfigEditActivity) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var ready = false
            instrumentation.runOnMainSync { ready = activity.binding.editor.isEnabled && activity.binding.editor.text.isNotBlank() }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("JSON preview did not load")
    }
    fun testChainComponentJsonChangesTheRebuiltCoreConfiguration() = isolated { component -> runBlocking {
        val chain = ProxyEntity(groupId = component.groupId).putBean(io.nekohasekai.sagernet.fmt.internal.ChainBean().apply {
            initializeDefaultValues(); proxies = listOf(component.id)
        })
        chain.id = SagerDatabase.proxyDao.addProxy(chain)
        val running = buildConfig(chain, forPreview = true)
        assertTrue(running.jsonSources.containsKey(component.id))
        val replacement = NodeJsonProfile.preview(component, NodeJson.OUTBOUND).text.let {
            NodeJson.parse(it).apply { addProperty("server_port", 8443) }.toString()
        }
        NodeJsonProfile.save(component.id, NodeJson.state(component.requireBean()), NodeJson.OUTBOUND, replacement)
        val changed = NodeJsonReload.changed(running.jsonSources, component.id) { ids ->
            SagerDatabase.proxyDao.getEntities(ids).associate { it.id to NodeJson.state(it.requireBean()) }
        }
        assertTrue(changed)
        val rebuilt = buildConfig(SagerDatabase.proxyDao.getById(chain.id)!!, forPreview = true)
        val tag = rebuilt.profileTagMap[chain.id]
        val actual = NodeJson.parse(rebuilt.config).getAsJsonArray("outbounds").single { it.asJsonObject["tag"].asString == tag }.asJsonObject
        assertEquals(8443, actual["server_port"].asInt)
        libcore.Libcore.newSingBoxInstance(rebuilt.config, moe.matsuri.nb4a.net.LocalResolverImpl).close()
    } }
    fun testEditorRecreationRetainsUnsavedTextWithoutWritingDatabase() = isolated { profile ->
        val before = NodeJson.state(profile.requireBean())
        var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ConfigEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); putExtra(NodeJsonMenu.PROFILE_ID, profile.id); putExtra(NodeJsonMenu.MODE, NodeJson.OUTBOUND)
        }) as ConfigEditActivity
        try {
            awaitEditor(activity)
            var draft = ""
            instrumentation.runOnMainSync {
                draft = activity.binding.editor.text.toString().replace("443", "8443")
                activity.binding.editor.setTextContent(draft)
            }
            val monitor = instrumentation.addMonitor(ConfigEditActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                activity = monitor.waitForActivityWithTimeout(10_000) as ConfigEditActivity
            } finally { instrumentation.removeMonitor(monitor) }
            awaitEditor(activity)
            instrumentation.runOnMainSync { assertEquals(draft, activity.binding.editor.text.toString()) }
            assertEquals(before, NodeJson.state(SagerDatabase.proxyDao.getById(profile.id)!!.requireBean()))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
