package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.Type
import org.junit.Assert.*
import org.junit.Test

class StandardV2RayEditorRecreationTest {
    private class Editor : StandardV2RaySettingsActivity() {
        lateinit var factoryBean: StandardV2RayBean
        var factoryReads = 0
        override fun createEntity(): StandardV2RayBean { factoryReads++; return factoryBean }
    }

    private fun node(kind: String): StandardV2RayBean = when (kind) {
        "trojan" -> TrojanBean()
        "http" -> HttpBean()
        else -> VMessBean().apply { alterId = if (kind == "vless") -1 else 0 }
    }.apply { initializeDefaultValues(); sni = "stored.invalid"; packetEncoding = 1 }

    private fun editor(existing: StandardV2RayBean?, factory: StandardV2RayBean): Editor {
        // Exercise the actual resolver without constructing an Android window or database.
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val result = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Editor::class.java) as Editor
        result.factoryBean = factory
        val entity = existing?.let { ProxyEntity().putBean(it) }
        ProfileSettingsActivity::class.java.getDeclaredField("proxyEntity\$delegate").apply {
            isAccessible = true; set(result, lazyOf(entity))
        }
        return result
    }

    private fun resolved(editor: Editor): StandardV2RayBean = StandardV2RaySettingsActivity::class.java
        .getDeclaredMethod("editorProtocolBean").apply { isAccessible = true }.invoke(editor) as StandardV2RayBean

    @Test fun existingVlessDoesNotNeedTheNewNodeIntentFlag() {
        for (mode in ConnectionSecurity.Mode.entries) {
            val stored = node("vless")
            if (mode == ConnectionSecurity.Mode.REALITY) stored.realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
            ConnectionSecurity.select(stored, mode)
            val activity = editor(stored, node("vmess"))
            assertNull(activity.tmpBean)
            assertSame(stored, resolved(activity))
            assertTrue(resolved(activity).isVLESS)
            assertEquals(0, activity.factoryReads)
            assertEquals("stored.invalid", stored.sni)
            assertEquals(1, stored.packetEncoding.toInt())
        }
    }

    @Test fun newNodesAndOtherProtocolsKeepTheirConcreteIdentity() {
        for (kind in listOf("vless", "vmess", "trojan", "http")) {
            val fresh = node(kind)
            assertSame(fresh, resolved(editor(null, fresh)))
            val stored = node(kind)
            val activity = editor(stored, node("vmess"))
            assertSame(stored, resolved(activity))
            assertEquals(0, activity.factoryReads)
        }
    }

    @Test fun initializedEditorRemainsTheFirstSourceWithoutReseedingInput() {
        val activity = editor(node("vmess"), node("vmess"))
        val edited = node("vless").apply { sni = "unsaved.invalid"; packetEncoding = 2 }
        activity.tmpBean = edited
        assertSame(edited, resolved(activity))
        assertEquals("unsaved.invalid", edited.sni)
        assertEquals(2, edited.packetEncoding.toInt())
        assertEquals(0, activity.factoryReads)
    }

    @Test fun recreatedTrojanRestoresPasswordBindingWithoutWritingTheCache() {
        val activity = editor(node("trojan"), node("vmess"))
        val uuid = PreferenceBinding(Type.Text, "uuid")
        val password = PreferenceBinding(Type.Text, "password")
        for ((name, binding) in listOf("uuid" to uuid, "password" to password)) {
            StandardV2RaySettingsActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, binding) }
        }
        val configure = StandardV2RaySettingsActivity::class.java.getDeclaredMethod("configureProtocolBindings", StandardV2RayBean::class.java).apply { isAccessible = true }
        configure.invoke(activity, resolved(activity))
        assertEquals("password", uuid.fieldName)
        assertTrue(password.disable)
        configure.invoke(activity, node("vmess"))
        assertEquals("uuid", uuid.fieldName)
        assertFalse(password.disable)
    }
}
