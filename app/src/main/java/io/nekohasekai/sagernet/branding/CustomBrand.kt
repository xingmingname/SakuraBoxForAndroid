package io.nekohasekai.sagernet.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.util.Base64
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.R
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject

internal data class CustomBrand(
    val themeName: String = "",
    val tileTitle: String = "",
    val appIcon: Bitmap? = null,
    val notificationIcon: Bitmap? = null,
    val tileIcon: Bitmap? = null,
) {
    companion object {
        const val MAX_NAME = 64

        fun cleanName(name: String): String = name.filterNot(Char::isISOControl).trim().let {
            // Take full characters when an external document ends in a surrogate pair.
            it.take(MAX_NAME).trimEnd { char -> char.isHighSurrogate() }
        }
    }
}

internal data class BrandAppearance(val theme: BrandTheme, val custom: CustomBrand? = null) {
    fun tileTitle(context: Context) = custom?.tileTitle?.ifBlank { null } ?: context.getString(theme.label)

    fun showLogo(view: ImageView) {
        val logo = custom?.appIcon
        if (logo != null) view.setImageBitmap(logo) else view.setImageResource(theme.logo)
    }

    fun notificationIcon(): BrandNotificationIcon = BrandNotificationIcon(
        theme.notificationIcon,
        if (Build.VERSION.SDK_INT >= 23) custom?.notificationIcon else null,
    )

    fun applyNotificationIcon(context: Context, builder: NotificationCompat.Builder) =
        notificationIcon().applyTo(context, builder)
}

internal data class BrandNotificationIcon(@DrawableRes val fallbackResource: Int, val bitmap: Bitmap?) {
    fun applyTo(context: Context, builder: NotificationCompat.Builder) {
        builder.setSmallIcon(fallbackResource)
        if (Build.VERSION.SDK_INT >= 23) {
            // Also replace IconCompat when returning to a resource on a reused builder.
            builder.setSmallIcon(
                bitmap?.let(IconCompat::createWithBitmap)
                    ?: IconCompat.createWithResource(context, fallbackResource)
            )
        }
    }
}

internal object CustomBrandStore {
    const val FILE_NAME = "custom-brand-theme.json"
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_IMAGE_BYTES = 320 * 1024

    private data class Cached(val file: File, val inode: Long, val size: Long, val modified: Long, val value: CustomBrand)
    private var cached: Cached? = null

    @Synchronized
    fun read(context: Context): CustomBrand {
        val file = File(context.filesDir, FILE_NAME)
        val stat = try { Os.stat(file.path) } catch (_: ErrnoException) { return CustomBrand() }
        cached?.let {
            if (it.file == file && it.inode == stat.st_ino && it.size == stat.st_size && it.modified == file.lastModified()) {
                return it.value
            }
        }
        val modified = file.lastModified()
        val result = if (stat.st_size in 1..MAX_FILE_BYTES.toLong()) {
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                fun text(key: String) = CustomBrand.cleanName(json.optString(key))
                CustomBrand(
                    themeName = text("themeName"),
                    tileTitle = text("tileTitle"),
                    appIcon = decode(json.optString("appIcon"), BrandImageKind.APP.size),
                    notificationIcon = decode(json.optString("notificationIcon"), BrandImageKind.NOTIFICATION.size),
                    tileIcon = decode(json.optString("tileIcon"), BrandImageKind.TILE.size),
                )
            } catch (_: Exception) { CustomBrand() }
        } else CustomBrand()
        cached = Cached(file, stat.st_ino, stat.st_size, modified, result)
        return result
    }

    @Synchronized
    fun write(context: Context, value: CustomBrand) {
        val json = JSONObject().apply {
            put("themeName", CustomBrand.cleanName(value.themeName))
            put("tileTitle", CustomBrand.cleanName(value.tileTitle))
            value.appIcon?.let { put("appIcon", encode(it, BrandImageKind.APP.size)) }
            value.notificationIcon?.let { put("notificationIcon", encode(it, BrandImageKind.NOTIFICATION.size)) }
            value.tileIcon?.let { put("tileIcon", encode(it, BrandImageKind.TILE.size)) }
        }
        val encoded = json.toString().toByteArray(Charsets.UTF_8)
        check(encoded.size <= MAX_FILE_BYTES)
        writeBrandFile(File(context.filesDir, FILE_NAME), encoded)
        cached = null
    }

    private fun encode(bitmap: Bitmap, size: Int): String {
        require(bitmap.width == size && bitmap.height == size)
        return ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) && it.size() <= MAX_IMAGE_BYTES)
            Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun decode(base64: String, size: Int): Bitmap? {
        if (base64.isEmpty() || base64.length > MAX_IMAGE_BYTES * 4 / 3 + 4) return null
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            if (bytes.size > MAX_IMAGE_BYTES) return null
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth != size || options.outHeight != size || options.outMimeType != "image/png") return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: IllegalArgumentException) { null }
    }
}
