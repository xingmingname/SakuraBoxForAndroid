package io.nekohasekai.sagernet.branding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.IOException

internal enum class BrandImageKind(val size: Int) {
    APP(256), NOTIFICATION(64), TILE(64),
}

internal object BrandImages {
    private const val MAX_INPUT_BYTES = 24 * 1024 * 1024
    private const val MAX_SOURCE_EDGE = 32768
    private const val MAX_SOURCE_PIXELS = 64_000_000L
    private const val PREVIEW_EDGE = 768

    fun import(context: Context, uri: Uri): Bitmap {
        val temporary = File.createTempFile("brand-import", ".image", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Image unavailable")
            input.use { source ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0
                    while (true) {
                        val n = source.read(buffer)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_INPUT_BYTES) throw IOException("Image exceeds size limit")
                        output.write(buffer, 0, n)
                    }
                }
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temporary.path, options)
            if (options.outWidth !in 1..MAX_SOURCE_EDGE || options.outHeight !in 1..MAX_SOURCE_EDGE ||
                options.outWidth.toLong() * options.outHeight > MAX_SOURCE_PIXELS
            ) {
                throw IOException("Invalid image dimensions")
            }
            val mime = options.outMimeType
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > PREVIEW_EDGE) {
                options.inSampleSize *= 2
            }
            options.inJustDecodeBounds = false
            val result = BitmapFactory.decodeFile(temporary.path, options) ?: throw IOException("Invalid image")
            return if (mime == "image/jpeg") orientJpeg(temporary, result) else result
        } finally {
            temporary.delete()
        }
    }

    private fun orientJpeg(file: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun adapt(source: Bitmap, kind: BrandImageKind, crop: Boolean): Bitmap? {
        val size = kind.size
        val monochrome = kind != BrandImageKind.APP
        val inset = if (monochrome) 6f else 0f
        val area = size - 2 * inset
        val widthRatio = area / source.width
        val heightRatio = area / source.height
        val scale = if (crop) maxOf(widthRatio, heightRatio) else minOf(widthRatio, heightRatio)
        val width = source.width * scale
        val height = source.height * scale

        // Mask only the image content. Transparent padding around an opaque image
        // must not be mistaken for foreground transparency in the user's artwork.
        val content = Bitmap.createBitmap(area.toInt(), area.toInt(), Bitmap.Config.ARGB_8888)
        Canvas(content).drawBitmap(
            source, null,
            RectF((area - width) / 2, (area - height) / 2, (area + width) / 2, (area + height) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        if (!monochrome) return content
        val edge = content.width
        val pixels = IntArray(edge * edge)
        content.getPixels(pixels, 0, edge, 0, 0, edge, edge)
        val mask = monochromeIconMask(pixels, edge)
        if (mask.none { it ushr 24 > 0 }) return null
        content.setPixels(mask, 0, edge, 0, 0, edge, edge)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(content, inset, inset, null)
        return result
    }
}
