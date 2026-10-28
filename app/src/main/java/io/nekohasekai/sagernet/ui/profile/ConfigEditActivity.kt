package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.nekohasekai.sagernet.fmt.NodeJson
import com.blacksquircle.ui.editorkit.insert
import com.blacksquircle.ui.language.json.JsonLanguage
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutEditConfigBinding
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.toStringPretty
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.ListListener
import moe.matsuri.nb4a.ui.ExtendedKeyboard
import org.json.JSONObject

class ConfigEditActivity : ThemedActivity() {

    var dirty = false
    var key = Key.SERVER_CONFIG
    var useConfigStore = false
    private val nodeId by lazy { intent.getLongExtra(NodeJsonMenu.PROFILE_ID, 0L) }
    private val nodeMode by lazy { intent.getIntExtra(NodeJsonMenu.MODE, NodeJson.OUTBOUND) }
    private val nodeModel by lazy { ViewModelProvider(this)[NodeJsonEditorModel::class.java] }
    private var updatingText = false
    private var enableRequested = false
    private var choosing = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                (requireActivity() as ConfigEditActivity).saveAndExit()
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    lateinit var binding: LayoutEditConfigBinding

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { requestEditorExit() }

        intent?.extras?.apply {
            getString("key")?.let { key = it }
            getString("useConfigStore")?.let { useConfigStore = true }
        }

        binding = LayoutEditConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.config_settings)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.editor.apply {
            language = JsonLanguage()
            setHorizontallyScrolling(true)
            if (nodeId != 0L) {
                isSaveEnabled = false
                setTextContent(nodeModel.text)
            } else if (useConfigStore) {
                setTextContent(DataStore.configurationStore.getString(key) ?: "")
            } else {
                setTextContent(DataStore.profileCacheStore.getString(key) ?: "")
            }
            addTextChangedListener {
                if (nodeId != 0L) {
                    if (!updatingText && nodeModel.state.value.loaded) nodeModel.text = text.toString()
                    return@addTextChangedListener
                }
                if (!dirty) {
                    dirty = true
                    DataStore.dirty = true
                }
            }
        }

        if (nodeId != 0L) {
            require(nodeMode == NodeJson.FULL || nodeMode == NodeJson.OUTBOUND)
            supportActionBar?.setTitle(if (nodeMode == NodeJson.FULL) R.string.json_full else R.string.json_outbound)
            lifecycleScope.launch {
                nodeModel.state.collect { state ->
                    if (isFinishing || isDestroyed) return@collect
                    binding.editor.isEnabled = state.loaded && !state.busy
                    if (state.loaded && binding.editor.text.toString() != nodeModel.text) {
                        updatingText = true
                        binding.editor.setTextContent(nodeModel.text)
                        updatingText = false
                    }
                    if (state.saved) {
                        if (state.runtimeWarning) android.widget.Toast.makeText(this@ConfigEditActivity, R.string.json_saved_reconnect, android.widget.Toast.LENGTH_LONG).show()
                        if (state.copied) android.widget.Toast.makeText(this@ConfigEditActivity, R.string.json_copy_done, android.widget.Toast.LENGTH_LONG).show()
                        setResult(RESULT_OK); finish()
                    }
                    if (state.choosing && !choosing) {
                        choosing = true
                        MaterialAlertDialogBuilder(this@ConfigEditActivity).setTitle(R.string.json_choose_outbound)
                            .setItems(nodeModel.choices.mapIndexed { i, text ->
                                val out = NodeJson.parse(text)
                                NodeJson.string(out, "tag") ?: "${NodeJson.string(out, "type")} #${i + 1}"
                            }.toTypedArray()) { _, index -> choosing = false; nodeModel.choose(index) }
                            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                            .setOnCancelListener { finish() }.show()
                    }
                    state.error?.let {
                        MaterialAlertDialogBuilder(this@ConfigEditActivity).setTitle(R.string.json_check_failed)
                            .setMessage(getString(R.string.json_error_detail, it))
                            .setPositiveButton(android.R.string.ok, null).show()
                        nodeModel.clearError()
                    }
                    if (state.loaded && intent.getBooleanExtra("enableJson", false) && !enableRequested) {
                        enableRequested = true
                        intent.removeExtra("enableJson")
                        saveNode(true)
                    }
                }
            }
            nodeModel.load(nodeId, nodeMode)
        }

        binding.actionTab.setOnClickListener {
            try {
                binding.editor.insert(binding.editor.tab())
            } catch (e: Exception) {
            }
        }
        binding.actionUndo.setOnClickListener {
            try {
                binding.editor.undo()
            } catch (_: Exception) {
            }
        }
        binding.actionRedo.setOnClickListener {
            try {
                binding.editor.redo()
            } catch (_: Exception) {
            }
        }
        binding.actionFormat.setOnClickListener {
            formatText()?.let {
                binding.editor.setTextContent(it)
            }
        }

        val extendedKeyboard = findViewById<ExtendedKeyboard>(R.id.extended_keyboard)
        extendedKeyboard.setKeyListener { char ->
            try {
                binding.editor.insert(char)
            } catch (e: Exception) {
            }
        }
        extendedKeyboard.setHasFixedSize(true)
        extendedKeyboard.submitList("{},:_\"".map { it.toString() })
        extendedKeyboard.setBackgroundColor(getColorAttr(R.attr.primaryOrTextPrimary))

        val keyboardContainer = findViewById<LinearLayout>(R.id.keyboard_container)
        ViewCompat.setOnApplyWindowInsetsListener(keyboardContainer) { v, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            v.updateLayoutParams<MarginLayoutParams> {
                // systemBar insets are applied to the bottom of the keyboard
                if (imeVisible) {
                    bottomMargin = imeInsets.bottom - systemBarInsets.bottom
                } else {
                    bottomMargin = 0
                }
            }

            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    fun formatText(): String? {
        try {
            val txt = binding.editor.text.toString()
            if (txt.isBlank()) {
                return ""
            }
            return if (nodeId != 0L) NodeJson.text(NodeJson.parse(txt)) else JSONObject(txt).toStringPretty()
        } catch (e: Exception) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
                .setMessage(e.readableMessage).show()
            return null
        }
    }

    fun saveAndExit() {
        if (nodeId != 0L) { saveNode(false); return }
        formatText()?.let {
            if (useConfigStore) {
                DataStore.configurationStore.putString(key, it)
            } else {
                DataStore.profileCacheStore.putString(key, it)
            }
            finish()
        }
    }

    private var exitDialog: AlertDialog? = null

    private fun requestEditorExit() {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        if (nodeId != 0L) {
            if (nodeModel.committing) return
            if (!nodeModel.dirty) { if (nodeModel.discard()) finish(); return }
            if (exitDialog?.isShowing == true) return
            exitDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.json_unsaved_title)
                .setMessage(R.string.json_unsaved_message)
                .setNeutralButton(R.string.json_continue, null)
                .setNegativeButton(R.string.json_discard) { _, _ -> if (nodeModel.discard()) finish() }
                .setPositiveButton(R.string.json_save) { _, _ -> saveNode(false) }
                .setOnDismissListener { exitDialog = null }.show()
            return
        }
        if (!dirty) { finish(); return }
        if (supportFragmentManager.fragments.any { it is UnsavedChangesDialogFragment }) return
        UnsavedChangesDialogFragment().apply { key() }
            .showNow(supportFragmentManager, UnsavedChangesDialogFragment::class.java.name)
    }

    @Deprecated("Use onBackPressedDispatcher")
    // Back is intentionally consumed by this page; calling super bypasses its exit policy.
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() = requestEditorExit()

    override fun onSupportNavigateUp(): Boolean {
        requestEditorExit()
        return true
    }

    override fun onDestroy() {
        exitDialog?.dismiss()
        exitDialog = null
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_apply_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> return onSupportNavigateUp()
            R.id.action_apply -> {
                saveAndExit()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun saveNode(forceEnable: Boolean) {
        if (!nodeModel.state.value.loaded || nodeModel.state.value.busy) return
        if (!forceEnable && !nodeModel.dirty && nodeModel.expected?.mode == nodeMode) { finish(); return }
        val needsCopy = nodeMode == NodeJson.FULL && nodeModel.subscription
        if (nodeModel.expected?.mode != nodeMode || needsCopy) {
            val message = getString(R.string.json_enable_message) +
                (if (nodeModel.expected?.mode in listOf(NodeJson.FULL, NodeJson.OUTBOUND, NodeJson.LEGACY)) "\n\n" + getString(R.string.json_switch_message) else "") +
                (if (needsCopy) "\n\n" + getString(R.string.json_copy_subscription) else "")
            MaterialAlertDialogBuilder(this).setTitle(R.string.json_enable_title).setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.json_save_enable) { _, _ -> nodeModel.save(nodeId, nodeMode, needsCopy) }.show()
        } else nodeModel.save(nodeId, nodeMode, false)
    }
}
