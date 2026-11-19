package io.nekohasekai.sagernet.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.nekohasekai.sagernet.R

internal object VpnNotificationChannel {
    private const val LEGACY_ID = "service-vpn"
    private const val STATUS_ID = "service-vpn-status"

    fun ensure(context: Context): String {
        if (Build.VERSION.SDK_INT < 26) return LEGACY_ID
        val manager = checkNotNull(context.getSystemService(NotificationManager::class.java))
        val legacy = manager.getNotificationChannel(LEGACY_ID)
        val status = manager.getNotificationChannel(STATUS_ID)
        val id = channelId(
            legacy?.importance,
            Build.VERSION.SDK_INT >= 29 && legacy?.hasUserSetImportance() == true,
            status != null,
        )

        // Never re-register an existing channel at a different importance. Android
        // can lower an unlocked channel, and won't raise a previously saved minimum.
        val existing = if (id == STATUS_ID) status else legacy
        val name = context.getText(if (id == STATUS_ID) R.string.service_vpn_status else R.string.service_vpn)
        if (existing == null) {
            manager.createNotificationChannel(
                NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) }
            )
        } else if (existing.name.toString() != name.toString()) {
            existing.name = name
            manager.createNotificationChannel(existing)
        }
        return id
    }

    internal fun channelId(legacyImportance: Int?, userSetImportance: Boolean, statusExists: Boolean): String =
        if (statusExists || (legacyImportance == NotificationManager.IMPORTANCE_MIN && !userSetImportance)) {
            STATUS_ID
        } else {
            LEGACY_ID
        }
}
