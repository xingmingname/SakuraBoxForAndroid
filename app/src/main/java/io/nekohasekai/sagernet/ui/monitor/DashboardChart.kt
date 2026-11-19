package io.nekohasekai.sagernet.ui.monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.format.Formatter
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.TrafficTotals
import kotlin.math.max

internal class DashboardChart(context: Context) : View(context) {
    val downloadColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    val uploadColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSecondary).let {
        if (it != downloadColor) it else if (ColorUtils.calculateLuminance(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)) > 0.5
        ) 0xff17608b.toInt() else 0xff83cfff.toInt()
    }
    private val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rx = Path()
    private val tx = Path()
    private var speed = true
    private var points = emptyList<DashboardRate>()
    private var buckets = emptyList<TrafficTotals?>()
    private var end = 0L
    private var span = 60_000L

    fun showSpeed(data: List<DashboardRate>, at: Long, range: Long) {
        if (speed && points === data && end == at && span == range) return
        speed = true
        points = data
        end = at
        span = range
        invalidate()
    }

    fun showHistory(data: List<TrafficTotals?>) {
        if (!speed && buckets == data) return
        speed = false
        buckets = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + 22 * density
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return
        val begin = end - span
        val visible = if (speed) points.filter { it.at >= begin } else emptyList()
        val peak = if (speed) visible.maxOfOrNull { max(it.bytes.rx, it.bytes.tx) } else
            buckets.maxOfOrNull { it?.let { point -> max(point.rx, point.tx) } ?: 0 }
        if (peak == null || (speed && visible.isEmpty()) || (!speed && buckets.all { it == null })) return
        val scale = peak.coerceAtLeast(1).toDouble()
        fun y(value: Long) = bottom - (value / scale * (bottom - top)).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = ink
        paint.alpha = 180
        paint.textSize = 11 * resources.displayMetrics.scaledDensity
        val label = Formatter.formatShortFileSize(context, peak)
        canvas.drawText(if (speed) context.getString(R.string.dashboard_per_second, label) else label,
            left, top - 5 * density, paint)
        paint.alpha = 36
        paint.strokeWidth = density
        for (line in 0..2) {
            val y = top + (bottom - top) * line / 2
            canvas.drawLine(left, y, right, y, paint)
        }
        if (speed) {
            rx.reset()
            tx.reset()
            val bin = (span / ((right - left) / (2 * density)).toLong().coerceIn(1, 240)).coerceAtLeast(1)
            var previous = Long.MIN_VALUE
            var index = 0
            var lastX = 0f
            var lastRx = 0f
            var lastTx = 0f
            var groups = 0
            while (index < visible.size) {
                val first = visible[index++]
                var rxPeak = first.bytes.rx
                var txPeak = first.bytes.tx
                while (index < visible.size && visible[index].at - first.at < bin) {
                    rxPeak = max(rxPeak, visible[index].bytes.rx)
                    txPeak = max(txPeak, visible[index++].bytes.tx)
                }
                val x = left + ((first.at - begin).toDouble() / span * (right - left)).toFloat()
                lastX = x
                lastRx = y(rxPeak)
                lastTx = y(txPeak)
                if (previous == Long.MIN_VALUE || first.at - previous > max(4000L, bin * 2)) {
                    rx.moveTo(x, lastRx); tx.moveTo(x, lastTx)
                } else {
                    rx.lineTo(x, lastRx); tx.lineTo(x, lastTx)
                }
                groups++
                previous = visible[index - 1].at
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * density
            paint.color = downloadColor
            canvas.drawPath(rx, paint)
            paint.color = uploadColor
            canvas.drawPath(tx, paint)
            if (groups == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(lastX, lastTx, 2 * density, paint)
                paint.color = downloadColor
                canvas.drawCircle(lastX, lastRx, 2 * density, paint)
            }
        } else {
            val step = (right - left) / buckets.size
            buckets.forEachIndexed { index, bytes ->
                if (bytes != null) {
                    val start = left + index * step
                    paint.color = downloadColor
                    canvas.drawRect(start + step * 0.08f, y(bytes.rx), start + step * 0.46f, bottom, paint)
                    paint.color = uploadColor
                    canvas.drawRect(start + step * 0.52f, y(bytes.tx), start + step * 0.90f, bottom, paint)
                }
            }
        }
    }
}
