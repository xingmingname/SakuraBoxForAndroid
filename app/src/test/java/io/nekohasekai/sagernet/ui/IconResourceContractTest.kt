package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

/** Cross-resource contracts; no Android runtime, VPN or native library needed. */
class IconResourceContractTest {
    private val app = File("src/main/AndroidManifest.xml").let {
        if (it.isFile) File(".") else File("app")
    }
    private val android = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File(app, path)).documentElement
    private fun drawable(name: String) = xml("src/main/res/drawable/$name.xml")
    private fun Element.attr(name: String) = getAttributeNS(android, name)
    private fun Element.nodes(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test fun idlePlaneAndSlashShareTheOriginalBusyPlane() {
        val idle = drawable("ic_service_idle")
        val paths = idle.nodes("path").associateBy { it.attr("name") }
        assertTrue(paths.containsKey("strike_thru_path"))
        assertEquals(1, idle.nodes("clip-path").count { it.attr("name") == "strike_thru_mask" })
        assertEquals(drawable("ic_service_busy").nodes("path").single().attr("pathData"),
            paths.getValue("holey_icon").attr("pathData"))
        assertTrue(paths.getValue("strike_thru_path").attr("pathData").contains("19.73 22"))
    }

    @Test fun animationsHaveExistingTargetsAndCompatiblePathMorphs() {
        for (state in listOf("stopped", "connecting", "connected", "stopping")) {
            val animation = drawable("ic_service_$state")
            assertEquals("animated-vector", animation.tagName)
            val base = drawable(animation.attr("drawable").substringAfter("/"))
            val names = (base.nodes("path") + base.nodes("clip-path")).map { it.attr("name") }
            assertTrue(animation.nodes("target").isNotEmpty())
            for (target in animation.nodes("target")) {
                assertTrue("$state missing ${target.attr("name")}", target.attr("name") in names)
                for (animator in target.nodes("objectAnimator")) {
                    assertEquals("@android:integer/config_mediumAnimTime", animator.attr("duration"))
                    if (animator.attr("propertyName") == "pathData") {
                        val tokens = Regex("[MmZzLlHhVvCcSsQqTtAa]|[-+]?(?:[0-9]*[.])?[0-9]+")
                        fun structure(value: String) = tokens.findAll(value).map {
                            if (it.value.singleOrNull()?.isLetter() == true) it.value else "number"
                        }.toList()
                        assertEquals("$state incompatible morph", structure(animator.attr("valueFrom")),
                            structure(animator.attr("valueTo")))
                    }
                }
            }
            if (state != "connected") {
                assertTrue("original path animation lost", animation.nodes("objectAnimator")
                    .all { it.attr("propertyName") == "pathData" })
            }
        }
    }

    @Test fun connectedStateKeepsTheSakuraArtwork() {
        assertEquals("@drawable/ic_service_active", drawable("ic_service_connected").attr("drawable"))
        assertEquals(drawable("ic_sakura_launcher_foreground").nodes("path").map { it.attr("pathData") },
            drawable("ic_service_active").nodes("path").map { it.attr("pathData") })
        assertEquals("outline", drawable("ic_service_connected").nodes("target").single().attr("name"))
    }

    @Test fun shortcutGeometryIsExactlyTheLauncherWithOnlyColorChanged() {
        val launcher = drawable("ic_sakura_launcher_foreground")
        val black = drawable("ic_sakura_shortcut_foreground")
        for (attr in listOf("width", "height", "viewportWidth", "viewportHeight")) {
            assertEquals(launcher.attr(attr), black.attr(attr))
        }
        val originalPaths = launcher.nodes("path")
        for (variant in listOf(black, drawable("ic_sakura_shortcut"))) {
            val paths = variant.nodes("path")
            assertEquals(originalPaths.size, paths.size)
            for ((original, path) in originalPaths.zip(paths)) {
                for (attr in listOf("pathData", "strokeWidth", "strokeLineCap", "strokeLineJoin", "fillType")) {
                    assertEquals("changed shape: $attr", original.attr(attr), path.attr(attr))
                }
                for (attr in listOf("strokeColor", "fillColor")) {
                    assertTrue(path.attr(attr) in listOf("", "#000000", "@android:color/transparent"))
                }
            }
        }
        val legacy = drawable("ic_sakura_shortcut")
        assertEquals("72", legacy.attr("viewportWidth"))
        assertEquals("72", legacy.attr("viewportHeight"))
        assertEquals("-18", legacy.nodes("group").single().attr("translateX"))
        assertEquals("-18", legacy.nodes("group").single().attr("translateY"))
        val adaptive = xml("src/main/res/drawable-v26/ic_qu_shadowsocks_launcher.xml")
        assertEquals("@drawable/ic_sakura_shortcut_foreground", adaptive.nodes("foreground").single().attr("drawable"))
        assertEquals("#FFFFFF", drawable("ic_sakura_shortcut_background").nodes("solid").single().attr("color"))
        assertEquals("#FFFFFF", xml("src/main/res/values/ic_launcher_background.xml")
            .nodes("color").single().textContent.trim())
    }

    @Test fun shortcutIntentsMatchEachBuildAndRegisteredActivities() {
        val properties = Properties().apply { File(app, "../nb4a.properties").inputStream().use { load(it) } }
        val pkg = properties.getProperty("PACKAGE_NAME")
        val manifest = xml("src/main/AndroidManifest.xml")
        val activities = manifest.nodes("activity").map {
            it.attr("name").let { name -> if (name.startsWith(".")) "io.nekohasekai.sagernet$name" else name }
        }
        val main = xml("src/main/res/xml/shortcuts.xml").nodes("shortcut")
        val debug = xml("src/debug/res/xml/shortcuts.xml").nodes("shortcut")
        assertEquals(listOf("toggle", "enable", "disable", "scan"), main.map { it.attr("shortcutId") })
        assertEquals(main.map { it.attr("shortcutId") }, debug.map { it.attr("shortcutId") })
        for ((base, variant) in main.zip(debug)) {
            val intent = base.nodes("intent").single()
            val debugIntent = variant.nodes("intent").single()
            assertEquals(pkg, intent.attr("targetPackage"))
            assertEquals("$pkg.debug", debugIntent.attr("targetPackage"))
            assertEquals(intent.attr("targetClass"), debugIntent.attr("targetClass"))
            assertTrue(intent.attr("targetClass") in activities)
            for (attr in listOf("icon", "shortcutShortLabel", "shortcutLongLabel")) {
                assertEquals(base.attr(attr), variant.attr(attr))
            }
            assertEquals(if (base.attr("shortcutId") == "scan") "@drawable/ic_qu_camera_launcher"
                else "@drawable/ic_qu_shadowsocks_launcher", base.attr("icon"))
        }
    }

    @Test fun shortcutAndAnimationDrawableReferencesExist() {
        val resources = File(app, "src/main/res").walkTopDown().filter { it.isFile }.map { it.nameWithoutExtension }.toSet()
        val files = listOf("ic_service_stopped", "ic_service_connecting", "ic_service_connected", "ic_service_stopping",
            "ic_qu_shadowsocks_launcher")
        for (name in files) {
            val text = File(app, "src/main/res/drawable/$name.xml").readText()
            for (match in Regex("@drawable/([a-z0-9_]+)").findAll(text)) {
                assertTrue("missing ${match.groupValues[1]}", match.groupValues[1] in resources)
            }
        }
    }
}
