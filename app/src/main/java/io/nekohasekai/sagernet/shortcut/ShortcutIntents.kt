package io.nekohasekai.sagernet.shortcut

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.util.Log
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.ui.TrustedShortcutActivity

object ShortcutIntents {
    const val PROFILE = "profile"

    fun trusted(context: Context, request: ShortcutRequest): Intent =
        Intent(context, TrustedShortcutActivity::class.java).setAction(request.operation.action).apply {
            request.profileId?.let { putExtra(PROFILE, it) }
        }

    fun profile(context: Context, id: Long): Intent {
        require(id > 0)
        // Pinning is platform-brokered only from API 26. Compat pinning on API 25
        // still uses INSTALL_SHORTCUT and needs the public confirmation gateway.
        return if (Build.VERSION.SDK_INT >= 26) trusted(context, ShortcutRequest(ShortcutOperation.TOGGLE, id))
        else Intent(context, QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN).putExtra(PROFILE, id)
    }

    /** Untrusted Bundles may throw during unparcelling. No service is bound on failure. */
    fun read(intent: Intent, operation: ShortcutOperation?): ShortcutRequest? = try {
        ShortcutRequest.parse(operation, intent.hasExtra(PROFILE), intent.extras?.get(PROFILE))
    } catch (_: RuntimeException) { null }

    /** Preserve IDs, names and icons. Static/immutable shortcuts update from XML;
     * failed or vendor-cached legacy shortcuts retain the confirmation gateway. */
    fun upgradePinnedShortcuts(context: Context) {
        if (Build.VERSION.SDK_INT < 25) return
        try {
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return
            val updates = manager.pinnedShortcuts.mapNotNull { shortcut ->
                if (shortcut.isImmutable) return@mapNotNull null
                val id = shortcut.id.removePrefix("shortcut-profile-").toLongOrNull()
                    ?.takeIf { it > 0 && shortcut.id == "shortcut-profile-$it" } ?: return@mapNotNull null
                val old = shortcut.intent ?: return@mapNotNull null
                if (old.component?.packageName != context.packageName ||
                    old.component?.className != QuickToggleShortcut::class.java.name ||
                    old.action != Intent.ACTION_MAIN) return@mapNotNull null
                val request = read(old, ShortcutOperation.TOGGLE)
                    ?.takeIf { it.profileId == id } ?: return@mapNotNull null
                ShortcutInfo.Builder(context, shortcut.id)
                    .setShortLabel(shortcut.shortLabel ?: return@mapNotNull null)
                    .setIntent(trusted(context, request)).build()
            }
            if (updates.isNotEmpty() && !manager.updateShortcuts(updates)) {
                Log.w("ShortcutSecurity", "Pinned shortcut update deferred")
            }
        } catch (_: RuntimeException) {
            // Locked user, platform rate limit or vendor implementation: safe old gateway remains.
            Log.w("ShortcutSecurity", "Pinned shortcut update unavailable")
        }
    }
}
