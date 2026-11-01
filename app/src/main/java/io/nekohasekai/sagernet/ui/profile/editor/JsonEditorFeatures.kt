package io.nekohasekai.sagernet.ui.profile.editor

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.blacksquircle.ui.editorkit.utils.EditorTheme
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutEditConfigBinding
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class JsonEditorFeatures(
    private val activity: ThemedActivity,
    private val binding: LayoutEditConfigBinding,
    savedState: Bundle?,
) {
    private val state = JsonEditorSearchState()
    private var searchJob: Job? = null
    private var released = false
    private val editor get() = binding.editor
    private val query get() = binding.jsonSearchQuery
    private val accent = MaterialColors.getColor(editor, R.attr.colorPrimary)
    private val muted = MaterialColors.getColor(editor, android.R.attr.textColorSecondary)
    private val decoration = JsonEditorDecoration(accent, muted,
        ColorUtils.setAlphaComponent(accent, 38), ColorUtils.setAlphaComponent(accent, 66),
        ColorUtils.setAlphaComponent(muted, 38))
    private val keyboardOpener = Runnable {
        if (!released && state.visible && query.hasFocus() && !activity.isFinishing && !activity.isDestroyed) {
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
        }
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        if (state.visible && query.hasFocus()) revealCurrent()
    }
    private val editorWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (state.visible) schedule(state.edited(start, before, count))
        }
        override fun afterTextChanged(s: Editable?) = Unit
    }
    private val queryWatcher: TextWatcher

    init {
        val dark = editor.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val scheme = if (dark) EditorTheme.DARCULA else EditorTheme.INTELLIJ_LIGHT
        editor.colorScheme = scheme.copy(
            backgroundColor = MaterialColors.getColor(editor, android.R.attr.colorBackground),
            cursorColor = accent,
        )
        editor.installPlugin(decoration)
        editor.addTextChangedListener(editorWatcher)
        editor.addOnLayoutChangeListener(layoutListener)
        query.isSaveEnabled = false
        queryWatcher = query.addTextChangedListener {
            if (state.visible) schedule(state.invalidate())
        }
        binding.jsonSearchPrevious.setOnClickListener { navigate(true) }
        binding.jsonSearchNext.setOnClickListener { navigate(false) }
        binding.jsonSearchClose.setOnClickListener { closeSearch() }
        query.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_SEARCH ||
                event?.let { it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_DOWN } == true) {
                navigate(event?.isShiftPressed == true)
                true
            } else false
        }
        render()
        if (savedState?.getBoolean(OPEN) == true) {
            state.open(savedState.getInt(ANCHOR))
            binding.jsonSearchBar.isVisible = true
            query.setText(savedState.getString(QUERY).orEmpty())
            schedule(state.invalidate())
        }
    }

    fun openSearch() {
        if (released || activity.isFinishing || activity.isDestroyed) return
        if (!state.visible) {
            state.open()
            binding.jsonSearchBar.isVisible = true
            schedule(state.invalidate())
        }
        query.requestFocus()
        query.removeCallbacks(keyboardOpener)
        query.post(keyboardOpener)
    }

    private fun schedule(request: Long) {
        searchJob?.cancel()
        render()
        if (released || !state.visible || query.text.isNullOrEmpty()) return
        searchJob = activity.lifecycleScope.launch {
            delay(120)
            if (released || !state.visible || request != state.revision) return@launch
            val needle = query.text.toString()
            // One immutable snapshot per debounced search, never the live Editable on a worker.
            val snapshot = editor.text.toString()
            val results = withContext(Dispatchers.Default) {
                val worker = coroutineContext
                JsonEditorSearch.find(snapshot, needle) { worker.ensureActive() }
            }
            if (!released && state.accept(request, results)) {
                render()
                if (query.hasFocus()) revealCurrent()
            }
        }
    }

    private fun render() {
        decoration.showMatches(state.matches, state.index)
        binding.jsonSearchCount.text = activity.getString(R.string.json_editor_search_count,
            if (state.index >= 0) state.index + 1 else 0, state.matches.size)
        binding.jsonSearchCount.contentDescription = activity.getString(R.string.json_editor_search_count_description,
            if (state.index >= 0) state.index + 1 else 0, state.matches.size)
        binding.jsonSearchPrevious.isEnabled = state.matches.size > 0
        binding.jsonSearchNext.isEnabled = state.matches.size > 0
    }

    private fun navigate(backwards: Boolean) {
        if (!state.visible || released) return
        state.navigate(backwards)
        render()
        revealCurrent()
    }

    private fun revealCurrent() {
        if (released || state.index < 0 || state.matches.textLength != editor.length()) return
        val layout = editor.layout ?: return
        val start = state.matches.start(state.index)
        val end = state.matches.end(state.index)
        if (start < 0 || end > editor.length()) return
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset((end - 1).coerceAtLeast(start))
        val availableHeight = (editor.height - editor.totalPaddingTop - editor.totalPaddingBottom).coerceAtLeast(1)
        val availableWidth = (editor.width - editor.totalPaddingLeft - editor.totalPaddingRight).coerceAtLeast(1)
        val top = layout.getLineTop(first)
        val bottom = layout.getLineBottom(last)
        val y = when {
            top < editor.scrollY -> top
            bottom > editor.scrollY + availableHeight -> if (bottom - top <= availableHeight) bottom - availableHeight else top
            else -> editor.scrollY
        }.coerceIn(0, (layout.height - availableHeight).coerceAtLeast(0))
        val left = minOf(layout.getPrimaryHorizontal(start), layout.getPrimaryHorizontal(end)).toInt()
        val right = maxOf(layout.getPrimaryHorizontal(start), layout.getPrimaryHorizontal(end)).toInt()
        val x = if (editor.isHorizontallyScrollableCompat()) {
            when {
                left < editor.scrollX -> left
                right > editor.scrollX + availableWidth -> if (right - left <= availableWidth) right - availableWidth else left
                else -> editor.scrollX
            }.coerceIn(0, (layout.width - availableWidth).coerceAtLeast(0))
        } else 0
        editor.abortFling()
        editor.scrollTo(x, y)
    }

    fun closeSearch() {
        searchJob?.cancel()
        query.removeCallbacks(keyboardOpener)
        state.close()
        binding.jsonSearchBar.isVisible = false
        query.clearFocus()
        query.text?.clear()
        render()
        editor.requestFocus()
    }

    fun saveState(out: Bundle) {
        out.putBoolean(OPEN, state.visible)
        out.putString(QUERY, query.text.toString())
        out.putInt(ANCHOR, state.anchor)
    }

    fun release() {
        released = true
        searchJob?.cancel()
        query.removeCallbacks(keyboardOpener)
        editor.removeTextChangedListener(editorWatcher)
        editor.removeOnLayoutChangeListener(layoutListener)
        query.removeTextChangedListener(queryWatcher)
        query.setOnEditorActionListener(null)
        binding.jsonSearchPrevious.setOnClickListener(null)
        binding.jsonSearchNext.setOnClickListener(null)
        binding.jsonSearchClose.setOnClickListener(null)
        editor.uninstallPlugin(JsonEditorDecoration.ID)
    }

    private companion object {
        const val OPEN = "jsonEditorSearchOpen"
        const val QUERY = "jsonEditorSearchQuery"
        const val ANCHOR = "jsonEditorSearchAnchor"
    }
}
