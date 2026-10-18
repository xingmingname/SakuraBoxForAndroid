package io.nekohasekai.sagernet.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.content.Intent
import android.os.Build
import android.test.InstrumentationTestCase
import android.util.Xml
import androidx.appcompat.content.res.AppCompatResources
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import io.nekohasekai.sagernet.R
import org.xmlpull.v1.XmlPullParser

/** Resource-only checks: no VPN, network access, credentials or database writes. */
@Suppress("DEPRECATION")
class BrandingResourcesTest : InstrumentationTestCase() {
    private val context get() = instrumentation.targetContext

    private fun draw(id: Int, size: Int): Bitmap {
        val drawable = requireNotNull(AppCompatResources.getDrawable(context, id))
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(this))
        }
    }

    fun testSystemIconsAreVisibleWhiteAlphaMasks() {
        for (id in intArrayOf(R.drawable.ic_sakura_notification, R.drawable.ic_service_idle,
            R.drawable.ic_service_busy, R.drawable.ic_service_active)) {
            val bitmap = draw(id, 96)
            assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(0, Color.alpha(bitmap.getPixel(95, 95)))
            var opaque = 0
            for (y in 0 until 96) for (x in 0 until 96) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 0) {
                    opaque++
                    assertEquals(255, Color.red(pixel))
                    assertEquals(255, Color.green(pixel))
                    assertEquals(255, Color.blue(pixel))
                }
            }
            assertTrue("empty or opaque-square system icon", opaque in 100..4500)
            bitmap.recycle()
        }
    }

    fun testForegroundFitsAdaptiveSafeCircle() {
        val bitmap = draw(R.drawable.ic_sakura_launcher_foreground, 216)
        var visible = 0
        for (y in 0 until 216) for (x in 0 until 216) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 0) {
                visible++
                val dx = x + 0.5 - 108
                val dy = y + 0.5 - 108
                assertTrue("foreground clipped outside 66dp safe circle", dx * dx + dy * dy <= 67 * 67)
            }
        }
        assertTrue(visible > 100)
        bitmap.recycle()
    }

    fun testServiceButtonAnimationsStillInflate() {
        for (id in intArrayOf(R.drawable.ic_service_stopped, R.drawable.ic_service_connecting,
            R.drawable.ic_service_connected, R.drawable.ic_service_stopping)) {
            assertNotNull(AnimatedVectorDrawableCompat.create(context, id))
        }
    }

    fun testApplicationNameAndAdaptiveLayers() {
        assertEquals("SakuraBox", context.getString(R.string.app_name))
        if (Build.VERSION.SDK_INT >= 26) {
            for (id in intArrayOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
                val icon = context.getDrawable(id) as AdaptiveIconDrawable
                assertNotNull(icon.foreground)
                assertNotNull(icon.background)
                if (Build.VERSION.SDK_INT >= 33) assertNotNull(icon.monochrome)
            }
        }
    }

    fun testShortcutForegroundOnlyChangesLauncherColor() {
        val pink = draw(R.drawable.ic_sakura_launcher_foreground, 216)
        val black = draw(R.drawable.ic_sakura_shortcut_foreground, 216)
        var visible = 0
        try {
            for (y in 0 until 216) for (x in 0 until 216) {
                val p = pink.getPixel(x, y)
                val b = black.getPixel(x, y)
                assertEquals("shortcut geometry changed", Color.alpha(p), Color.alpha(b))
                if (Color.alpha(b) > 0) {
                    visible++
                    assertEquals(0, Color.red(b))
                    assertEquals(0, Color.green(b))
                    assertEquals(0, Color.blue(b))
                }
            }
            assertTrue(visible > 100)
        } finally {
            pink.recycle()
            black.recycle()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val icon = context.getDrawable(R.drawable.ic_qu_shadowsocks_launcher) as AdaptiveIconDrawable
            val background = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                icon.background.setBounds(0, 0, 32, 32)
                icon.background.draw(Canvas(background))
                assertEquals(Color.WHITE, background.getPixel(16, 16))
            } finally {
                background.recycle()
            }
        }
    }

    fun testAllShortcutIntentsResolveInsideInstalledPackage() {
        context.resources.getXml(R.xml.shortcuts).use { parser ->
            var count = 0
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                    val intent = Intent.parseIntent(context.resources, parser, Xml.asAttributeSet(parser))
                    val component = requireNotNull(intent.component)
                    assertEquals(context.packageName, component.packageName)
                    val info = context.packageManager.getActivityInfo(component, 0)
                    assertTrue(info.exported)
                    count++
                }
                parser.next()
            }
            assertEquals(4, count)
        }
    }
}
