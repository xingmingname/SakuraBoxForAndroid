package io.nekohasekai.sagernet.shortcut

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.test.InstrumentationTestCase
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.bg.TileService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.ui.QuickDisableShortcut
import io.nekohasekai.sagernet.ui.QuickEnableShortcut
import io.nekohasekai.sagernet.ui.TrustedShortcutActivity

/** Package-manager and real Bundle tests; no VPN start, DB mutation or real credentials.
 * This is not a substitute for the separate-UID probe in tools/security001-probe. */
@Suppress("DEPRECATION")
class ShortcutBoundaryTest : InstrumentationTestCase() {
    fun testPinnedProfileUsesBrokerOnlyWhenFrameworkPinningExists() {
        val intent = ShortcutIntents.profile(instrumentation.targetContext, 2L)
        assertEquals(if (Build.VERSION.SDK_INT >= 26) TrustedShortcutActivity::class.java.name
            else QuickToggleShortcut::class.java.name, intent.component!!.className)
        assertEquals(2L, intent.getLongExtra("profile", -1L))
    }

    fun testInstalledComponentBoundary() {
        val context = instrumentation.targetContext
        val pm = context.packageManager
        assertFalse(pm.getActivityInfo(ComponentName(context, TrustedShortcutActivity::class.java), 0).exported)
        listOf(QuickToggleShortcut::class.java, QuickEnableShortcut::class.java, QuickDisableShortcut::class.java).forEach {
            assertTrue(pm.getActivityInfo(ComponentName(context, it), 0).exported)
        }
        assertEquals("android.permission.BIND_VPN_SERVICE", pm.getServiceInfo(ComponentName(context, VpnService::class.java), 0).permission)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", pm.getServiceInfo(ComponentName(context, TileService::class.java), 0).permission)
    }

    fun testForgedExtrasDoNotAuthorizeOrChangeCommand() {
        val context = instrumentation.targetContext
        val forged = Intent(Intent.ACTION_MAIN).apply {
            putExtra("authorized", true)
            putExtra("token", "not-a-capability")
            putExtra("callingPackage", context.packageName)
            putExtra(Intent.EXTRA_REFERRER_NAME, "android-app://${context.packageName}")
            putExtra("profile", "2")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        assertNull(ShortcutIntents.read(forged, ShortcutOperation.TOGGLE))
        forged.putExtra("profile", 2L)
        assertNull(ShortcutIntents.read(forged, ShortcutOperation.DISABLE))
        val parsed = ShortcutIntents.read(forged, ShortcutOperation.TOGGLE)!!
        val consent = ShortcutConsent(parsed)
        consent.cancel()
        assertNull(consent.confirm(true, true, true))
    }

    fun testTrustedIntentDoesNotForwardUntrustedExtrasOrFlags() {
        val intent = ShortcutIntents.trusted(instrumentation.targetContext, ShortcutRequest(ShortcutOperation.TOGGLE, 2))
        assertEquals(TrustedShortcutActivity::class.java.name, intent.component!!.className)
        assertEquals(ShortcutOperation.TOGGLE.action, intent.action)
        assertEquals(setOf("profile"), intent.extras!!.keySet())
        assertEquals(2L, intent.getLongExtra("profile", -1))
        assertEquals(0, intent.flags)
        assertNull(intent.data)
        assertNull(intent.clipData)
        assertNull(intent.selector)
    }
}
