package io.nekohasekai.sagernet.branding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.system.Os
import androidx.annotation.DrawableRes
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal enum class BrandTheme(
    @StringRes val label: Int,
    @StringRes val aboutTitle: Int,
    @DrawableRes val logo: Int,
    @DrawableRes val previewIcon: Int,
    @DrawableRes val notificationIcon: Int,
) {
    SAKURA(
        R.string.app_name, R.string.app_name_long, R.drawable.ic_sakura_logo,
        R.mipmap.ic_launcher, R.drawable.ic_sakura_notification,
    ),
    NEKO(
        R.string.brand_neko, R.string.brand_neko_long, R.mipmap.ic_launcher_neko,
        R.mipmap.ic_launcher_neko, R.drawable.ic_neko_notification,
    ),
    CUSTOM(
        R.string.app_name, R.string.app_name_long, R.drawable.ic_sakura_logo,
        R.mipmap.ic_launcher, R.drawable.ic_sakura_notification,
    ),
}

internal object BrandThemeManager {
    const val ACTION_CHANGED = "io.nekohasekai.sagernet.action.BRAND_THEME_CHANGED"
    private const val SELECTION = "brand-theme-selection"

    fun current(context: Context): BrandTheme =
        readSelection(File(context.filesDir, SELECTION)) ?: BrandTheme.SAKURA

    fun appearance(context: Context): BrandAppearance {
        val theme = current(context)
        return BrandAppearance(theme, if (theme == BrandTheme.CUSTOM) CustomBrandStore.read(context) else null)
    }

    fun custom(context: Context): CustomBrand = CustomBrandStore.read(context)

    fun saveCustom(context: Context, config: CustomBrand) {
        CustomBrandStore.write(context, config)
        announce(context)
    }

    @MainThread
    fun select(context: Context, brand: BrandTheme) {
        if (current(context) == brand) return
        try {
            writeBrandFile(File(context.filesDir, SELECTION), brand.name.toByteArray(Charsets.US_ASCII))
        } catch (error: Exception) {
            throw IllegalStateException("Could not persist brand theme", error)
        }
        announce(context)
    }

    private fun announce(context: Context) {
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    @Suppress("DEPRECATION") // The File constructor is required up to API 28.
    fun observe(context: Context, onChanged: () -> Unit): AutoCloseable {
        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean()
        val deliver = Runnable { if (!closed.get()) onChanged() }
        fun enqueue() {
            if (closed.get()) return
            handler.removeCallbacks(deliver)
            handler.post(deliver)
        }

        val observer = object : FileObserver(context.filesDir.absolutePath, FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path == SELECTION || path == CustomBrandStore.FILE_NAME) enqueue()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = enqueue()
        }
        observer.startWatching()
        val registered = try {
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            true
        } catch (error: RuntimeException) {
            Logs.w(error)
            false
        }
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                observer.stopWatching()
                if (registered) context.unregisterReceiver(receiver)
                handler.removeCallbacks(deliver)
            }
        }
    }

    private fun readSelection(file: File): BrandTheme? {
        if (file.length() !in 1..16) return null
        return try {
            val name = file.readText(Charsets.US_ASCII)
            BrandTheme.entries.firstOrNull { it.name == name }
        } catch (_: java.io.IOException) { null }
    }
}

// The reader opens only the committed filename in every process. A same-directory
// rename leaves either the old snapshot or the complete new snapshot visible.
internal fun writeBrandFile(file: File, bytes: ByteArray) {
    val pending = File.createTempFile(file.name, ".tmp", file.parentFile)
    try {
        pending.outputStream().use {
            it.write(bytes)
            it.fd.sync()
        }
        Os.rename(pending.path, file.path)
    } finally {
        pending.delete()
    }
}
