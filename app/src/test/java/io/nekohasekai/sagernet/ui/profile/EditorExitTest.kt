package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import android.content.DialogInterface
import android.os.Handler
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentHostCallback
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class EditorExitTest {
    private class Editor : ProfileSettingsActivity<SOCKSBean>() {
        var finishes = 0
        override fun finish() { finishes++ }
        override fun createEntity() = SOCKSBean()
        override fun SOCKSBean.init() {}
        override fun SOCKSBean.serialize() { error("Discard must not serialize or save") }
        override fun androidx.preference.PreferenceFragmentCompat.createPreferences(
            savedInstanceState: android.os.Bundle?, rootKey: String?) {}
    }

    private class Host(context: Context, handler: Handler) : FragmentHostCallback<FragmentActivity>(context, handler, 0) {
        override fun onGetHost(): FragmentActivity = error("Unused by requireActivity")
    }

    private class Buttons(context: Context) : AlertDialog.Builder(context) {
        var discard: DialogInterface.OnClickListener? = null
        override fun setTitle(titleId: Int): AlertDialog.Builder = this
        override fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder = this
        override fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder {
            discard = listener
            return this
        }
        override fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): AlertDialog.Builder = this
    }

    private fun <T> allocate(type: Class<T>): T {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return type.cast(unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type))
    }

    @Test fun discardCancelsPendingSaveAndFinishesWithoutSerializing() {
        val editor = allocate(Editor::class.java)
        val pending = Job()
        ProfileSettingsActivity::class.java.getDeclaredField("saveJob").apply {
            isAccessible = true; set(editor, pending)
        }
        ProfileSettingsActivity::class.java.getDeclaredMethod("discardAndExit").apply {
            isAccessible = true; invoke(editor)
        }
        assertEquals(1, editor.finishes)
        assertTrue(pending.isCancelled)
        assertTrue(ProfileSettingsActivity::class.java.getDeclaredField("discardingChanges").apply {
            isAccessible = true
        }.getBoolean(editor))
    }

    @Test fun actualUnsavedDialogDiscardButtonDoesNotEnterSaveValidation() {
        val editor = allocate(Editor::class.java)
        val host = allocate(Host::class.java)
        FragmentHostCallback::class.java.getDeclaredField("mActivity").apply { isAccessible = true; set(host, editor) }
        val prompt = allocate(ProfileSettingsActivity.UnsavedChangesDialogFragment::class.java)
        Fragment::class.java.getDeclaredField("mHost").apply { isAccessible = true; set(prompt, host) }
        val buttons = allocate(Buttons::class.java)
        prompt.javaClass.getDeclaredMethod("prepare", AlertDialog.Builder::class.java, DialogInterface.OnClickListener::class.java)
            .apply { isAccessible = true }.invoke(prompt, buttons, DialogInterface.OnClickListener { _, _ -> })
        assertNotNull(buttons.discard)
        buttons.discard!!.onClick(null, DialogInterface.BUTTON_NEGATIVE)
        assertEquals(1, editor.finishes)
    }

    private fun source(name: String): String {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .first { File(it, "app/src/main/java/io/nekohasekai/sagernet/ui/profile/ProfileSettingsActivity.kt").isFile }
        return File(root, "app/src/main/java/io/nekohasekai/sagernet/ui/profile/$name.kt").readText()
    }

    @Test fun staticContractSystemAndToolbarBackUseTheSameExitPath() {
        for (name in listOf("ProfileSettingsActivity", "ConfigEditActivity")) {
            val text = source(name)
            assertTrue(name, text.contains("onBackPressedDispatcher.addCallback(this) { requestEditorExit() }"))
            assertTrue(name, text.contains("override fun onBackPressed() = requestEditorExit()"))
            assertTrue(name, text.substringAfter("override fun onSupportNavigateUp(): Boolean {").trimStart().startsWith("requestEditorExit()"))
            assertTrue(name, text.contains("android.R.id.home"))
            assertTrue(name, text.contains("fragments.any { it is UnsavedChangesDialogFragment }"))
            assertTrue(name, text.contains(".showNow(supportFragmentManager, UnsavedChangesDialogFragment::class.java.name)"))
        }
    }

    @Test fun staticContractSaveCallbackCapturesTheHostBeforeLaunchingWork() {
        val text = source("ProfileSettingsActivity")
        val callback = text.substringAfter("setPositiveButton(R.string.yes)").substringBefore("setNegativeButton(R.string.no)")
        assertTrue(callback.contains(".requestSaveAndExit()"))
        assertFalse(callback.contains("runOnDefaultDispatcher"))
        assertTrue(text.contains("saveJob = lifecycleScope.launch(Dispatchers.Default) { saveAndExit() }"))
        val save = text.substringAfter("open suspend fun saveAndExit()")
        assertFalse(save.contains("ProtocolOptions.validate"))
        assertFalse(save.contains("MtlsOptions.validateWithCore"))
        assertTrue(save.indexOf("ProtocolOptions.parse") >= 0)
        assertTrue(save.indexOf("ProtocolOptions.parse") < save.indexOf("ProfileManager.createProfile"))
        assertTrue(save.indexOf("ensureActive()", save.indexOf("catch (e: Exception)")) < save.indexOf("ProfileManager.createProfile"))
    }

    @Test fun staticContractJsonLoadingCanBeAbandonedButCommitIsNotPretendedCancelled() {
        val text = source("ConfigEditActivity")
        assertTrue(text.contains("if (nodeModel.committing) return"))
        assertFalse(text.contains("if (nodeModel.state.value.busy && nodeModel.state.value.loaded) return"))
        assertTrue(text.contains("if (exitDialog?.isShowing == true) return"))
        assertTrue(text.contains(".setNegativeButton(R.string.json_discard) { _, _ -> if (nodeModel.discard()) finish() }"))
        assertTrue(text.contains(".setPositiveButton(R.string.json_save) { _, _ -> saveNode(false) }"))
    }
}
