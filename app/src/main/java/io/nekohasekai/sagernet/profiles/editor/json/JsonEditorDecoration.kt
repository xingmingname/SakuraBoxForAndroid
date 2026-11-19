package io.nekohasekai.sagernet.ui.profile.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Editable
import com.blacksquircle.ui.editorkit.model.ColorScheme
import com.blacksquircle.ui.editorkit.plugin.base.EditorPlugin
import com.blacksquircle.ui.editorkit.widget.TextProcessor

internal class JsonEditorDecoration(
    private val accent: Int,
    private val muted: Int,
    matchColor: Int,
    currentMatchColor: Int,
    dividerColor: Int,
) : EditorPlugin(ID) {
    private val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
        color = muted
    }
    private val active = Paint(normal).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = accent
    }
    private val background = Paint()
    private val divider = Paint().apply { color = dividerColor }
    private val found = Paint().apply { color = matchColor }
    private val current = Paint().apply { color = currentMatchColor }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
    }
    private val selectionPath = Path()
    private val digits = CharArray(10)
    private var originalLeft = 0
    private var gap = 0f
    private var digitCount = 0
    private var fontSize = -1f
    private var addedPadding = 0
    var matches = JsonSearchMatches.EMPTY
        private set
    var currentIndex = -1
        private set

    override fun onAttached(editText: TextProcessor) {
        originalLeft = editText.paddingLeft
        gap = 4f * editText.resources.displayMetrics.density
        outline.strokeWidth = editText.resources.displayMetrics.density
        super.onAttached(editText)
        updateGutter()
    }

    override fun onColorSchemeChanged(colorScheme: ColorScheme) {
        background.color = colorScheme.backgroundColor
    }

    fun showMatches(results: JsonSearchMatches, index: Int) {
        matches = results
        currentIndex = if (index in 0 until results.size) index else -1
        if (isAttached) editText.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = updateGutter()
    override fun afterTextChanged(text: Editable?) = updateGutter()
    override fun setTextContent(text: CharSequence) = updateGutter()
    override fun setTextSize(size: Float) = updateGutter()

    private fun updateGutter() {
        if (!isAttached) return
        var lines = structure.lineCount.coerceAtLeast(1)
        var count = 1
        while (lines >= 10) { count++; lines /= 10 }
        if (count == digitCount && fontSize == editText.textSize) return
        digitCount = count
        fontSize = editText.textSize
        normal.textSize = fontSize
        active.textSize = fontSize
        addedPadding = (maxOf(normal.measureText("8"), active.measureText("8")) * count + gap * 3).toInt()
        editText.setPadding(originalLeft + addedPadding, editText.paddingTop,
            editText.paddingRight, editText.paddingBottom)
    }

    override fun drawBehind(canvas: Canvas?) {
        val layout = editText.layout ?: return
        if (canvas == null || matches.size == 0 || matches.textLength != editText.length()) return
        val first = layout.getLineForVertical((editText.scrollY - editText.totalPaddingTop).coerceAtLeast(0))
        val last = layout.getLineForVertical(editText.scrollY + editText.height - editText.totalPaddingTop)
        val from = layout.getLineStart(first)
        val until = layout.getLineEnd(last)
        val saved = canvas.save()
        canvas.clipRect((editText.scrollX + editText.totalPaddingLeft).toFloat(),
            (editText.scrollY + editText.totalPaddingTop).toFloat(),
            (editText.scrollX + editText.width - editText.totalPaddingRight).toFloat(),
            (editText.scrollY + editText.height - editText.totalPaddingBottom).toFloat())
        canvas.translate(editText.totalPaddingLeft.toFloat(), editText.totalPaddingTop.toFloat())
        matches.forEachVisibleRange(from, until) { start, end ->
            selectionPath.reset()
            layout.getSelectionPath(start, end, selectionPath)
            canvas.drawPath(selectionPath, found)
        }
        if (currentIndex in 0 until matches.size) {
            val start = maxOf(from, matches.start(currentIndex))
            val end = minOf(until, matches.end(currentIndex))
            if (start < end) {
                selectionPath.reset()
                layout.getSelectionPath(start, end, selectionPath)
                canvas.drawPath(selectionPath, current)
                canvas.drawPath(selectionPath, outline)
            }
        }
        canvas.restoreToCount(saved)
    }

    override fun onDraw(canvas: Canvas?) {
        val layout = editText.layout ?: return
        if (canvas == null) return
        val left = editText.scrollX.toFloat()
        val right = left + editText.totalPaddingLeft
        val top = editText.scrollY.toFloat()
        val bottom = top + editText.height
        canvas.drawRect(left, top, right, bottom, background)
        val saved = canvas.save()
        canvas.clipRect(left, top, right, bottom)
        val x = right - gap * 2
        val valid = currentIndex in 0 until matches.size && matches.textLength == editText.length()
        val activeStart = if (valid) structure.getLineForIndex(matches.start(currentIndex)) else -1
        val activeEnd = if (valid) structure.getLineForIndex(matches.end(currentIndex) - 1) else -1
        val first = layout.getLineForVertical((editText.scrollY - editText.totalPaddingTop).coerceAtLeast(0))
        val last = layout.getLineForVertical(editText.scrollY + editText.height - editText.totalPaddingTop)
        var previous = -1
        for (visual in first..last) {
            val logical = structure.getLineForIndex(layout.getLineStart(visual))
            if (logical != previous) {
                var number = logical + 1
                var at = digits.size
                do { digits[--at] = ('0'.code + number % 10).toChar(); number /= 10 } while (number > 0)
                canvas.drawText(digits, at, digits.size - at, x,
                    (layout.getLineBaseline(visual) + editText.totalPaddingTop).toFloat(),
                    if (logical in activeStart..activeEnd && valid) active else normal)
            }
            previous = logical
        }
        canvas.drawLine(right - gap, top, right - gap, bottom, divider)
        canvas.restoreToCount(saved)
    }

    override fun onDetached(editText: TextProcessor) {
        showMatches(JsonSearchMatches.EMPTY, -1)
        editText.setPadding(originalLeft, editText.paddingTop, editText.paddingRight, editText.paddingBottom)
        super.onDetached(editText)
    }

    companion object { const val ID = "json-editor-decoration" }
}
