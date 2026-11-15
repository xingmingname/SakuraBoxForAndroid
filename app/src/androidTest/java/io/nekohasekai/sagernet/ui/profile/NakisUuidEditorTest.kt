package io.nekohasekai.sagernet.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.test.InstrumentationTestCase
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.nakis.NakisBean
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

@Suppress("DEPRECATION")
class NakisUuidEditorTest : InstrumentationTestCase() {
    private data class Editor(val dialog: AlertDialog, val field: EditText, val toggle: View)

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(30)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("UUID dialog did not finish laying out")
    }

    private fun openEditor(activity: NakisSettingsActivity, widthDp: Int? = null): Editor {
        awaitMain { activity.supportFragmentManager.findFragmentById(R.id.settings)?.view != null }
        lateinit var editor: Editor
        instrumentation.runOnMainSync {
            val row = activity.child.findPreference<EditTextPreference>("nakis.option.uuid")!!
            assertEquals("••••••••", row.summary)
            activity.child.onDisplayPreferenceDialog(row)
            activity.supportFragmentManager.executePendingTransactions()
            val dialog = activity.supportFragmentManager.fragments.filterIsInstance<DialogFragment>()
                .single { it.dialog?.isShowing == true }.requireDialog() as AlertDialog
            if (widthDp != null) {
                val width = (widthDp * activity.resources.displayMetrics.density).roundToInt()
                dialog.window!!.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            val field = dialog.findViewById<EditText>(android.R.id.edit)!!
            val layout = generateSequence(field.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
            val toggle = layout.findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
            editor = Editor(dialog, field, toggle)
        }
        awaitMain { editor.field.layout != null && editor.field.width > 0 && editor.toggle.isShown }
        return editor
    }

    fun testUuidVisibilityScrollingCopyPasteAndReopen() = runBlocking {
        assertTrue("Use a dedicated Debug test installation", BuildConfig.DEBUG)
        assertFalse("Never run against an active VPN", DataStore.serviceState.canStop)
        val original = "6c1c4a52-3b8e-4d9a-9a52-0d4f5f0f7a11"
        val replacement = "AD305D54-75B4-431B-ADB2-EB6B9E546014"
        val group = ProxyGroup(name = "Nakis UUID instrumentation ${System.nanoTime()}")
        group.id = SagerDatabase.groupDao.createGroup(group)
        val context = instrumentation.targetContext
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val activities = mutableListOf<NakisSettingsActivity>()
        var previousClip: ClipData? = null
        var changedClip = false
        try {
            val entity = ProxyEntity(groupId = group.id).putBean(NakisBean().apply {
                initializeDefaultValues()
                name = "Synthetic Nakis UUID"
                serverAddress = "example.invalid"
                protocolOptionsJson = """{"uuid":"$original"}"""
            })
            entity.id = SagerDatabase.proxyDao.addProxy(entity)
            fun start(profile: ProxyEntity) = (instrumentation.startActivitySync(profile.settingIntent(context, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NakisSettingsActivity).also(activities::add)

            val activity = start(entity)
            val (dialog, field, toggle) = openEditor(activity, widthDp = 240)
            instrumentation.runOnMainSync {
                assertEquals(original, field.text.toString())
                assertTrue(field.transformationMethod is PasswordTransformationMethod)
                assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
                assertTrue(field.imeOptions and EditorInfo.IME_FLAG_NO_EXTRACT_UI != 0)
                assertTrue(toggle.performClick())
                assertFalse(field.transformationMethod is PasswordTransformationMethod)
            }
            instrumentation.waitForIdleSync()
            var startScroll = 0
            instrumentation.runOnMainSync {
                assertEquals(1, field.lineCount)
                assertNull(field.ellipsize)
                assertTrue(field.compoundPaddingEnd > field.paddingEnd)
                assertTrue(field.layout.getLineWidth(0) > field.width - field.compoundPaddingLeft - field.compoundPaddingRight)
                field.setSelection(0)
                field.bringPointIntoView(0)
                startScroll = field.scrollX
                field.setSelection(original.length)
                field.bringPointIntoView(original.length)
            }
            awaitMain { field.scrollX > startScroll }
            instrumentation.runOnMainSync {
                assertEquals(original.length, field.selectionEnd)
                previousClip = clipboard.primaryClip
                changedClip = true
                field.selectAll()
                assertTrue(field.onTextContextMenuItem(android.R.id.copy))
                assertEquals(original, clipboard.primaryClip?.getItemAt(0)?.text.toString())
                clipboard.setPrimaryClip(ClipData.newPlainText("", replacement))
                assertTrue(field.onTextContextMenuItem(android.R.id.paste))
                assertEquals(replacement, field.text.toString())
                assertTrue(toggle.performClick())
                assertTrue(field.transformationMethod is PasswordTransformationMethod)
                assertEquals(replacement, field.text.toString())
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            }
            awaitMain { !dialog.isShowing && activity.child.findPreference<EditTextPreference>("nakis.option.uuid")?.text == replacement }
            activity.saveAndExit()
            instrumentation.waitForIdleSync()
            val saved = SagerDatabase.proxyDao.getById(entity.id)!!
            assertEquals(replacement, ProtocolOptions.parse(saved.requireBean().protocolOptionsJson)["uuid"].asString)

            val reopened = openEditor(start(saved))
            instrumentation.runOnMainSync {
                assertTrue(reopened.field.transformationMethod is PasswordTransformationMethod)
                assertTrue(reopened.toggle.performClick())
                assertEquals(replacement, reopened.field.text.toString())
            }
        } finally {
            instrumentation.runOnMainSync {
                if (changedClip) {
                    val clip = previousClip
                    if (clip != null) clipboard.setPrimaryClip(clip)
                    else if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
                    else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                activities.forEach { if (!it.isFinishing) it.finish() }
            }
            SagerDatabase.proxyDao.deleteByGroup(group.id)
            SagerDatabase.groupDao.deleteGroup(group)
        }
    }
}

