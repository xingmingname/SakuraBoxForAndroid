package io.nekohasekai.sagernet.bg

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.branding.BrandNotificationIcon
import io.nekohasekai.sagernet.branding.BrandThemeManager
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = ForegroundNotificationPublisher.NOTIFICATION_ID
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) ent.displayName() else "[$gn] ${ent.displayName()}"
        }
    }

    @Volatile var listenPostSpeed = true

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        useBuilder {
            if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                it.setContentText(speedDetail)
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                it.setContentText(speedSimple)
            }
            it.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
        publish()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            it.setContentTitle(newTitle)
        }
        publish()
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority =
                if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        }
        publish()
    }

    private val showDirectSpeed = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    private val buildLock = Mutex()
    private val foregroundLock = Any()
    private var closed = false
    private val serviceContext: Service = service as Service
    private val publisher = ForegroundNotificationPublisher(
        serviceContext,
        builder,
        foreground = { id, notification ->
            if (Build.VERSION.SDK_INT >= 34) {
                serviceContext.startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                serviceContext.startForeground(id, notification)
            }
        },
        update = { id, notification -> NotificationManagerCompat.from(serviceContext).notify(id, notification) },
    )
    private val brandUpdates: AutoCloseable

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        brandUpdates = BrandThemeManager.observe(service) {
            service.data.scope.launch {
                try {
                    publish(onlyIfIconChanged = true)
                } catch (error: SecurityException) {
                    Logs.w(error)
                }
            }
        }
    }

    // Called in the service startup job. Foreground failures propagate to its
    // existing error/cleanup path instead of leaving a connected background VPN.
    suspend fun initialize() {
        updateActions()
        publish(allowInitial = true)
    }

    private suspend fun updateActions() {
        service as Context
        useBuilder {
            it.clearActions()

            val closeAction = NotificationCompat.Action.Builder(
                0, service.getText(R.string.stop), PendingIntent.getBroadcast(
                    service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(closeAction)

            val switchAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.action_switch), PendingIntent.getActivity(
                    service, 0, Intent(service, SwitchActivity::class.java), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(switchAction)

            val resetUpstreamAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.reset_connections),
                PendingIntent.getBroadcast(
                    service, 0, Intent(Action.RESET_UPSTREAM_CONNECTIONS), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(resetUpstreamAction)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (service.data.state == BaseService.State.Connected) {
            listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
        }
    }


    private suspend fun publish(allowInitial: Boolean = false, onlyIfIconChanged: Boolean = false) {
        buildLock.withLock {
            synchronized(foregroundLock) {
                if (closed || (!allowInitial && !publisher.hasPublished)) return
                val icon = BrandThemeManager.appearance(service as Context).notificationIcon()
                publisher.publish(icon, onlyIfIconChanged)
            }
        }
    }

    fun destroy() {
        synchronized(foregroundLock) {
            if (closed) return
            closed = true
            listenPostSpeed = false
            val context = service as Service
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.stopForeground(Service.STOP_FOREGROUND_REMOVE)
                } else {
                    context.stopForeground(true)
                }
            } finally {
                try {
                    brandUpdates.close()
                } finally {
                    context.unregisterReceiver(this)
                }
            }
        }
    }
}


// The service protects this publisher with its builder and foreground lifecycle locks.
internal class ForegroundNotificationPublisher(
    private val context: Context,
    private val builder: NotificationCompat.Builder,
    private val foreground: (Int, Notification) -> Unit,
    private val update: (Int, Notification) -> Unit,
) {
    companion object {
        const val NOTIFICATION_ID = 1
    }

    private var postedIcon: BrandNotificationIcon? = null
    val hasPublished: Boolean get() = postedIcon != null

    fun publish(icon: BrandNotificationIcon, onlyIfIconChanged: Boolean = false) {
        val iconChanged = postedIcon != icon
        if (onlyIfIconChanged && !iconChanged) return

        icon.applyTo(context, builder)
        val notification = builder.build()
        if (!hasPublished) {
            foreground(NOTIFICATION_ID, notification)
        } else if (iconChanged) {
            try {
                // Re-submit the same ID to ActivityManager as well as updating the
                // notification. This uses the service's current foreground instance.
                foreground(NOTIFICATION_ID, notification)
            } catch (error: SecurityException) {
                Logs.w(error)
                update(NOTIFICATION_ID, notification)
            } catch (error: IllegalStateException) {
                // Some systems re-check foreground-start eligibility on re-submission.
                Logs.w(error)
                update(NOTIFICATION_ID, notification)
            }
        } else {
            update(NOTIFICATION_ID, notification)
        }
        postedIcon = icon
    }
}
