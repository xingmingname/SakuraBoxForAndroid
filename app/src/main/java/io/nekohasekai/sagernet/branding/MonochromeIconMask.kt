package io.nekohasekai.sagernet.branding

import kotlin.math.abs

internal fun monochromeIconMask(pixels: IntArray, edge: Int): IntArray {
    require(edge > 0 && pixels.size == edge * edge)
    // Exclude letterbox columns or rows introduced by fit-to-square.
    var left = edge
    var right = -1
    var top = edge
    var bottom = -1
    for (i in pixels.indices) {
        if (pixels[i] ushr 24 == 0) continue
        val x = i % edge
        val y = i / edge
        left = minOf(left, x)
        right = maxOf(right, x)
        top = minOf(top, y)
        bottom = maxOf(bottom, y)
    }
    if (right < left) return IntArray(pixels.size)

    val hasTransparency = (top..bottom).any { y ->
        (left..right).any { x -> pixels[y * edge + x] ushr 24 < 224 }
    }
    val corners = intArrayOf(
        pixels[top * edge + left], pixels[top * edge + right],
        pixels[bottom * edge + left], pixels[bottom * edge + right],
    )

    fun difference(a: Int, b: Int): Int = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)),
        abs((a and 255) - (b and 255)),
    )
    return IntArray(pixels.size) { i ->
        val pixel = pixels[i]
        val opacity = pixel ushr 24
        val alpha = if (hasTransparency || opacity == 0) opacity else {
            val distance = corners.minOf { difference(pixel, it) }
            ((distance - 18) * 5).coerceIn(0, 255)
        }
        if (alpha > 0) (alpha shl 24) or 0x00FFFFFF else 0
    }
}
