package io.nekohasekai.sagernet.ui.profile.editor

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.appcompat.app.AlertDialog
import com.blacksquircle.ui.editorkit.utils.EditorTheme
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.profile.ConfigEditActivity

@Suppress("DEPRECATION")
class JsonEditorFeaturesTest : InstrumentationTestCase() {
    private fun awaitMain(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            SystemClock.sleep(20)
        }
        fail("Editor search did not settle")
    }

    private fun host(block: (ConfigEditActivity) -> Unit) {
        assertTrue("Use a dedicated Debug test installation", BuildConfig.DEBUG)
        assertFalse("Do not test with a running VPN", DataStore.serviceState.canStop)
        val oldDirty = DataStore.dirty
        val key = "instrumentation-json-editor-${System.nanoTime()}"
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ConfigEditActivity::class.java)
            .putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConfigEditActivity
        try {
            instrumentation.waitForIdleSync()
            block(activity)
            assertNull(DataStore.profileCacheStore.getString(key))
        } finally {
            instrumentation.runOnMainSync { if (!activity.isFinishing) activity.finish() }
            instrumentation.waitForIdleSync()
            DataStore.dirty = oldDirty
        }
    }

    private fun open(activity: ConfigEditActivity) {
        assertTrue(activity.onKeyShortcut(KeyEvent.KEYCODE_F,
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F, 0, KeyEvent.META_CTRL_ON)))
        assertEquals(View.VISIBLE, activity.binding.jsonSearchBar.visibility)
    }

    fun testRealSearchUiCyclesMatchesAndKeepsDocumentAndSelection() = host { activity ->
        val doc = "{\n  \"tls\": true,\n  \"TLS\": false,\n  \"note\": \"Tls\"\n}"
        instrumentation.runOnMainSync {
            activity.binding.editor.setTextContent(doc)
            activity.binding.editor.setSelection(2, 5)
            activity.dirty = false
            DataStore.dirty = false
            open(activity)
            activity.binding.jsonSearchQuery.setText("tls")
        }
        awaitMain { activity.binding.jsonSearchCount.text.toString() == "1/3" }
        instrumentation.runOnMainSync {
            val decoration = activity.binding.editor.findPlugin<JsonEditorDecoration>(JsonEditorDecoration.ID)!!
            assertEquals(3, decoration.matches.size)
            assertEquals(0, decoration.currentIndex)
            activity.binding.jsonSearchNext.performClick()
            assertEquals("2/3", activity.binding.jsonSearchCount.text.toString())
            assertEquals(1, decoration.currentIndex)
            activity.binding.jsonSearchNext.performClick()
            activity.binding.jsonSearchNext.performClick()
            assertEquals("1/3", activity.binding.jsonSearchCount.text.toString())
            activity.binding.jsonSearchPrevious.performClick()
            assertEquals("3/3", activity.binding.jsonSearchCount.text.toString())
            assertTrue(activity.binding.jsonSearchQuery.hasFocus())
            assertEquals(2, activity.binding.editor.selectionStart)
            assertEquals(5, activity.binding.editor.selectionEnd)
            assertEquals(doc, activity.binding.editor.text.toString())
            assertFalse(activity.dirty)
            assertFalse(DataStore.dirty)
            activity.binding.jsonSearchClose.performClick()
            assertEquals(View.GONE, activity.binding.jsonSearchBar.visibility)
            assertEquals(0, decoration.matches.size)
            assertEquals(-1, decoration.currentIndex)
            assertEquals(doc, activity.binding.editor.text.toString())
            assertFalse(activity.dirty)
        }
    }

    fun testEditingCurrentResultAndNoMatchRemainSafe() = host { activity ->
        instrumentation.runOnMainSync {
            activity.binding.editor.setTextContent("{\"name\":\"中文\"}")
            open(activity)
            activity.binding.jsonSearchQuery.setText("中文")
        }
        awaitMain { activity.binding.jsonSearchCount.text.toString() == "1/1" }
        instrumentation.runOnMainSync { activity.binding.editor.text.clear() }
        awaitMain { activity.binding.jsonSearchCount.text.toString() == "0/0" }
        instrumentation.runOnMainSync {
            assertFalse(activity.binding.jsonSearchNext.isEnabled)
            val decoration = activity.binding.editor.findPlugin<JsonEditorDecoration>(JsonEditorDecoration.ID)!!
            assertEquals(0, decoration.matches.size)
            assertEquals(1, activity.binding.editor.structure.lineCount)
            activity.binding.jsonSearchClose.performClick()
        }
    }

    fun testSearchDoesNotBlockBackOrToolbarExitWhenDocumentIsUnchanged() {
        for (toolbar in listOf(false, true)) host { activity ->
            instrumentation.runOnMainSync {
                open(activity)
                activity.binding.jsonSearchQuery.setText("missing")
                if (toolbar) activity.onSupportNavigateUp() else activity.onBackPressedDispatcher.onBackPressed()
                assertTrue(activity.isFinishing)
            }
        }
    }

    fun testSearchKeepsOriginalDiscardConfirmationAndDoesNotSave() = host { activity ->
        instrumentation.runOnMainSync {
            activity.binding.editor.setTextContent("{broken")
            open(activity)
            activity.binding.jsonSearchQuery.setText("broken")
            activity.onBackPressedDispatcher.onBackPressed()
            val fragment = activity.supportFragmentManager.fragments.single {
                it is ConfigEditActivity.UnsavedChangesDialogFragment
            } as ConfigEditActivity.UnsavedChangesDialogFragment
            (fragment.dialog as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertTrue(activity.isFinishing)
        }
    }

    private fun fixture(wrap: Boolean, doc: String, test: (TextProcessor, JsonEditorDecoration) -> Unit) {
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_SagerNet)
            val editor = TextProcessor(context).apply {
                colorScheme = EditorTheme.INTELLIJ_LIGHT
                setHorizontallyScrolling(!wrap)
            }
            val decoration = JsonEditorDecoration(Color.MAGENTA, Color.GRAY, 0x40ff00ff, 0x60ff00ff, Color.LTGRAY)
            editor.installPlugin(decoration)
            try {
                editor.setTextContent(doc)
                editor.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
                editor.layout(0, 0, 240, 180)
                test(editor, decoration)
            } finally { editor.uninstallPlugin(JsonEditorDecoration.ID) }
        }
    }

    private fun draw(editor: TextProcessor): Bitmap = Bitmap.createBitmap(editor.width, editor.height,
        Bitmap.Config.ARGB_8888).also { editor.draw(Canvas(it)) }

    private fun gutterPixels(bitmap: Bitmap, editor: TextProcessor, color: Int): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until editor.paddingLeft.coerceAtMost(bitmap.width)) {
            if (bitmap.getPixel(x, y) == color) count++
        }
        return count
    }

    fun testRealAndVisualLinesStaySeparateAndEmptyTextHasLineOne() {
        fixture(true, "一行".repeat(80) + "\nsecond\n") { editor, decoration ->
            assertEquals(3, editor.structure.lineCount)
            assertTrue(editor.layout.lineCount > 3)
            val padding = editor.paddingLeft
            val matches = JsonEditorSearch.find(editor.text, "second")
            decoration.showMatches(matches, 0)
            assertEquals(padding, editor.paddingLeft)
            val line = editor.layout.getLineForOffset(matches.start(0))
            editor.scrollTo(0, editor.layout.getLineTop(line))
            draw(editor).also { bitmap ->
                assertTrue("Current logical line number must be accented", gutterPixels(bitmap, editor, Color.MAGENTA) > 0)
                bitmap.recycle()
            }
            decoration.showMatches(JsonSearchMatches.EMPTY, -1)
            draw(editor).also { bitmap ->
                assertEquals(0, gutterPixels(bitmap, editor, Color.MAGENTA))
                bitmap.recycle()
            }
            editor.text.clear()
            assertEquals(1, editor.structure.lineCount)
            editor.scrollTo(0, 0)
            draw(editor).also { bitmap ->
                assertTrue("Empty document must draw line one", gutterPixels(bitmap, editor, Color.GRAY) > 0)
                bitmap.recycle()
            }
        }
    }

    fun testHighlightDoesNotChangeImeCompositionOrSelection() {
        fixture(false, "{\"name\":\"中文输入\"}") { editor, decoration ->
            editor.setSelection(2, 5)
            BaseInputConnection.setComposingSpans(editor.text)
            val from = BaseInputConnection.getComposingSpanStart(editor.text)
            val end = BaseInputConnection.getComposingSpanEnd(editor.text)
            val original = editor.text.toString()
            decoration.showMatches(JsonEditorSearch.find(editor.text, "中文"), 0)
            draw(editor).recycle()
            decoration.showMatches(JsonSearchMatches.EMPTY, -1)
            draw(editor).recycle()
            assertEquals(original, editor.text.toString())
            assertEquals(2, editor.selectionStart)
            assertEquals(5, editor.selectionEnd)
            assertEquals(from, BaseInputConnection.getComposingSpanStart(editor.text))
            assertEquals(end, BaseInputConnection.getComposingSpanEnd(editor.text))
        }
    }
}
