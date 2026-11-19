package moe.matsuri.nb4a.ui

import android.content.Context
import androidx.core.app.NotificationCompat
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.branding.BrandThemeManager
import io.nekohasekai.sagernet.ktx.Logs

class ConnectionTestNotification(val context: Context, val title: String) {
    private val channelId = "connection-test"
    private val notificationId = 1001

    fun updateNotification(progress: Int, max: Int, finished: Boolean) {
        try {
            if (finished) {
                SagerNet.notification.cancel(notificationId)
                return
            }
            val builder = NotificationCompat.Builder(context, channelId)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setContentText("$progress / $max").setProgress(max, progress, false)
            BrandThemeManager.appearance(context).applyNotificationIcon(context, builder)
            SagerNet.notification.notify(notificationId, builder.build())
        } catch (e: Exception) {
            Logs.w(e)
        }
    }
}