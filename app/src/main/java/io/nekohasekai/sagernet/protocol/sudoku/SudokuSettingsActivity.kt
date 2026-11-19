package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreference
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class SudokuSettingsActivity : ProfileSettingsActivity<SudokuBean>() {
    override fun createEntity() = SudokuBean().applyDefaultValues()
    private val bindings = PreferenceBindingManager().apply {
        add(PreferenceBinding(Type.Text, "name"))
        add(PreferenceBinding(Type.Text, "serverAddress"))
        add(PreferenceBinding(Type.TextToInt, "serverPort"))
    }
    override fun SudokuBean.init() { optionsCache.clear(); bindings.writeToCacheAll(this) }
    override fun SudokuBean.serialize() { bindings.fromCacheAll(this) }
    private val emptyChoice = "__sudoku_preserved_empty__"
    private val textSummary = Preference.SummaryProvider<EditTextPreference> { SudokuPreferenceDisplay.text(it.text) }
    private val choiceSummary = Preference.SummaryProvider<ListPreference> {
        SudokuPreferenceDisplay.choice(it.value, it.entry, emptyChoice)
    }
    private val groups = mutableListOf<PreferenceCategory>()
    private data class OptionRow(val parent: PreferenceGroup, var preference: Preference, var editable: Boolean)
    private val optionRows = mutableMapOf<String, OptionRow>()
    private val unsetRows = mutableMapOf<String, Preference>()
    private var renderedOptions: JsonObject? = null
    private val pendingRefresh = SudokuPreferenceUpdates.Pending()
    private var priorityRow: Preference? = null
    private var retainedRow: Preference? = null
    private var syncingOptions = false

    private val optionsCache = SudokuOptions.EditorCache()
    private fun options() = optionsCache.read(DataStore.editingId,
        DataStore.profileCacheStore.getString(ProtocolAdvancedPreferences.CACHE_KEY))

    override fun onDestroy() {
        optionsCache.clear()
        pendingRefresh.clear()
        renderedOptions = null
        optionRows.clear(); unsetRows.clear(); groups.clear()
        priorityRow = null; retainedRow = null
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        super.onPreferenceDataStoreChanged(store, key)
        if (key == ProtocolAdvancedPreferences.CACHE_KEY) requestOptionsRefresh()
    }

    override fun onResume() {
        super.onResume()
        // JSON takeover can change in another editor without writing the native options cache.
        requestOptionsRefresh()
    }

    private fun requestOptionsRefresh(field: SudokuOptions.Field? = null) = runOnUiThread {
        if (isDestroyed) return@runOnUiThread
        val fragment = supportFragmentManager.findFragmentById(R.id.settings) as? ProfileSettingsActivity.MyPreferenceFragmentCompat
            ?: return@runOnUiThread
        val target = fragment.view ?: return@runOnUiThread
        if (!pendingRefresh.request(target, field?.path)) return@runOnUiThread
        target.post {
            val forced = pendingRefresh.drain(target) ?: return@post
            if (!isDestroyed && fragment.isAdded && fragment.view === target) fragment.refreshOptions(forced)
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        pendingRefresh.clear(); renderedOptions = null
        optionRows.clear(); unsetRows.clear(); groups.clear()
        // Keep stable Preference IDs and dispatch only actual insertions/removals.
        preferenceManager.preferenceComparisonCallback = PreferenceManager.SimplePreferenceComparisonCallback()
        preferenceScreen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen.addPreference(EditTextPreference(context).apply {
            key = "name"; setTitle(R.string.profile_name); setIcon(R.drawable.ic_social_emoji_symbols)
            summaryProvider = textSummary
        })
        val basic = PreferenceCategory(context).apply { key = "sudoku.group.BASIC"; setTitle(R.string.proxy_cat) }
        groups.add(basic)
        preferenceScreen.addPreference(basic)
        for ((keyName, label, icon) in listOf(
            Triple("serverAddress", R.string.server_address, R.drawable.ic_hardware_router),
            Triple("serverPort", R.string.server_port, R.drawable.ic_maps_directions_boat))) {
            basic.addPreference(EditTextPreference(context).apply {
                key = keyName; setTitle(label); setIcon(icon)
                summaryProvider = textSummary
                if (keyName == "serverPort") setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
            })
        }
        val root = options()
        for (field in SudokuPreferenceLayout.fields(SudokuPreferenceLayout.Section.BASIC)) addOption(basic, field, root)
        renderOptions(root)
    }

    private fun PreferenceFragmentCompat.renderOptions(root: JsonObject = options()) {
        for (section in SudokuPreferenceLayout.Section.entries.filter { it != SudokuPreferenceLayout.Section.BASIC }) {
            val titleRes = when (section) {
                SudokuPreferenceLayout.Section.BASIC -> error("Basic settings already created")
                SudokuPreferenceLayout.Section.ADVANCED -> R.string.sudoku_advanced
                SudokuPreferenceLayout.Section.HTTPMASK -> R.string.sudoku_httpmask
                SudokuPreferenceLayout.Section.LEGACY -> R.string.sudoku_legacy
                SudokuPreferenceLayout.Section.DIAL -> R.string.sudoku_dial
                SudokuPreferenceLayout.Section.RESOLVER -> R.string.sudoku_resolver
            }
            val category = PreferenceCategory(requireContext()).apply {
                key = "sudoku.group.$section"; setTitle(titleRes)
                initialExpandedChildrenCount = if (section.collapsed) 0 else Int.MAX_VALUE
            }
            groups.add(category); preferenceScreen.addPreference(category)
            for (field in SudokuPreferenceLayout.fields(section)) {
                addOption(category, field, root)
                if (field.kind in setOf(SudokuOptions.Kind.BOOLEAN, SudokuOptions.Kind.LIST)) {
                    val unset = Preference(requireContext()).apply {
                        key = "sudoku.unset.${field.path}"
                        title = getString(R.string.sudoku_unset_field, getString(SudokuFieldTitles.titles.getValue(field.path)))
                        isVisible = SudokuPreferenceUpdates.canUnset(root, field)
                        setOnPreferenceClickListener { update(field, null); true }
                    }
                    unsetRows[field.path] = unset
                    category.addPreference(unset)
                }
            }
        }
        priorityRow = Preference(requireContext()).apply {
            key = "sudoku.jsonPriority"
            setTitle(R.string.protocol_manual_override_priority); isSelectable = false
            isVisible = NodeJson.enabled(currentEditorBean())
        }.also { groups.first().addPreference(it) }
        retainedRow = Preference(requireContext()).apply {
            key = "sudoku.retained"
            setTitle(R.string.sudoku_retained_fields); isSelectable = false
        }.also { groups.first().addPreference(it) }
        refreshRetainedFields(root)
        renderedOptions = root
    }

    private fun PreferenceFragmentCompat.addOption(parent: PreferenceGroup, field: SudokuOptions.Field, root: JsonObject) {
        val preference = optionPreference(field, root)
        parent.addPreference(preference)
        optionRows[field.path] = OptionRow(parent, preference, SudokuOptions.editable(root, field))
    }

    private fun PreferenceFragmentCompat.refreshRetainedFields(root: JsonObject) {
        val retained = SudokuOptions.retainedFields(root)
        retainedRow?.apply {
            isVisible = retained.isNotEmpty()
            if (retained.isNotEmpty()) summary = SudokuPreferenceDisplay.text(getString(R.string.sudoku_retained_fields_summary, retained.joinToString(", ")))
        }
    }

    private fun PreferenceFragmentCompat.refreshOptions(forced: Set<String>) {
        val before = renderedOptions ?: return
        val root = options()
        syncingOptions = true
        try {
            for (field in SudokuPreferenceUpdates.changedFields(before, root, forced)) {
                val row = optionRows.getValue(field.path)
                val editable = SudokuOptions.editable(root, field)
                if (row.editable != editable) {
                    // Only an unsupported JSON representation requires changing this one row's type.
                    val replacement = optionPreference(field, root).apply { order = row.preference.order }
                    row.parent.removePreference(row.preference)
                    row.parent.addPreference(replacement)
                    row.preference = replacement; row.editable = editable
                } else if (editable) syncOptionValue(row.preference, field, root)
                unsetRows[field.path]?.isVisible = SudokuPreferenceUpdates.canUnset(root, field)
            }
        } finally {
            syncingOptions = false
        }
        priorityRow?.isVisible = NodeJson.enabled(currentEditorBean())
        refreshRetainedFields(root)
        renderedOptions = root
    }

    private fun syncOptionValue(pref: Preference, field: SudokuOptions.Field, root: JsonObject) {
        val existing = SudokuOptions.text(root, field)
        when (pref) {
            is ListPreference -> {
                val value: String
                val entries: Array<String>
                val values: Array<String>
                if (field.kind == SudokuOptions.Kind.TABLE_LIMIT) {
                    entries = field.choices.map { if (it == "0") getString(R.string.sudoku_table_limit_unlimited) else it }.toTypedArray()
                    values = field.choices.toTypedArray(); value = existing
                } else {
                    val choices = (field.choices + existing.takeIf { it.isNotEmpty() }.orEmpty()).filter { it.isNotEmpty() }.distinct()
                    val explicitEmpty = SudokuOptions.raw(root, field.path) != null && existing.isEmpty()
                    entries = (listOf(getString(androidx.preference.R.string.not_set)) + choices + if (explicitEmpty) listOf(getString(R.string.sudoku_empty_value)) else emptyList()).toTypedArray()
                    values = (listOf("") + choices + if (explicitEmpty) listOf(emptyChoice) else emptyList()).toTypedArray()
                    value = if (explicitEmpty) emptyChoice else existing
                }
                val changedEntries = !pref.entries.contentEquals(entries)
                val changedValues = !pref.entryValues.contentEquals(values)
                if (changedEntries) pref.entries = entries
                if (changedValues) pref.entryValues = values
                pref.setDefaultValue(value)
                if (changedEntries || changedValues || pref.value != value) pref.value = value
            }
            is SwitchPreference -> {
                val current = SudokuOptions.raw(root, field.path)
                val checked = current?.asBoolean ?: (field.path == "enable_pure_downlink")
                pref.setDefaultValue(checked)
                if (pref.isChecked != checked) pref.isChecked = checked
                pref.summary = if (current == null) "" else getString(if (checked) R.string.sudoku_true else R.string.sudoku_false)
            }
            is MultiSelectListPreference -> {
                val raw = SudokuOptions.raw(root, field.path)
                val selected = if (raw?.isJsonArray == true) raw.asJsonArray.map { it.asString }.toSet() else setOfNotNull(raw?.asString)
                val choices = (field.choices + selected).distinct().toTypedArray()
                if (!pref.entries.contentEquals(choices)) pref.entries = choices
                if (!pref.entryValues.contentEquals(choices)) pref.entryValues = choices
                pref.setDefaultValue(selected)
                if (pref.values != selected) pref.values = selected
                pref.summary = if (raw == null) "" else SudokuPreferenceDisplay.text(selected.joinToString(", "))
            }
            is EditTextPreference -> {
                pref.setDefaultValue(existing)
                if (pref.text != existing) pref.text = existing
            }
        }
    }

    private fun PreferenceFragmentCompat.update(field: SudokuOptions.Field, input: String?, selection: Set<String>? = null) {
        val root = options() // Read current cache-store content, never a preference's older render snapshot.
        val updated = if (selection == null) SudokuOptions.set(root, field, input)
            else SudokuOptions.setSelection(root, field, selection)
        DataStore.profileCacheStore.putString(ProtocolAdvancedPreferences.CACHE_KEY, updated.toString())
        requestOptionsRefresh(field)
    }

    private fun PreferenceFragmentCompat.optionPreference(field: SudokuOptions.Field, root: JsonObject): Preference {
        val context = requireContext()
        val label = getString(SudokuFieldTitles.titles.getValue(field.path))
        if (!SudokuOptions.editable(root, field)) return Preference(context).apply {
            key = "sudoku.${field.path}"; title = label; isSingleLineTitle = false
            setSummary(R.string.sudoku_retained_json); isSelectable = false
        }
        val existing = SudokuOptions.text(root, field)
        val pref: Preference = when {
            field.kind == SudokuOptions.Kind.TABLE_LIMIT -> SimpleMenuPreference(context).apply {
                entries = field.choices.map { if (it == "0") getString(R.string.sudoku_table_limit_unlimited) else it }.toTypedArray()
                entryValues = field.choices.toTypedArray()
                bindProtocolValue(existing)
                summaryProvider = choiceSummary
            }
            field.kind == SudokuOptions.Kind.BOOLEAN -> SwitchPreference(context).apply {
                val current = SudokuOptions.raw(root, field.path)
                setDefaultValue(current?.asBoolean ?: (field.path == "enable_pure_downlink"))
                isChecked = current?.asBoolean ?: (field.path == "enable_pure_downlink")
                summary = if (current == null) "" else getString(if (isChecked) R.string.sudoku_true else R.string.sudoku_false)
            }
            field.kind == SudokuOptions.Kind.CHOICE -> SimpleMenuPreference(context).apply {
                val choices = (field.choices + existing.takeIf { it.isNotEmpty() }.orEmpty()).filter { it.isNotEmpty() }.distinct()
                val explicitEmpty = SudokuOptions.raw(root, field.path) != null && existing.isEmpty()
                entries = (listOf(getString(androidx.preference.R.string.not_set)) + choices + if (explicitEmpty) listOf(getString(R.string.sudoku_empty_value)) else emptyList()).toTypedArray()
                entryValues = (listOf("") + choices + if (explicitEmpty) listOf(emptyChoice) else emptyList()).toTypedArray()
                bindProtocolValue(if (explicitEmpty) emptyChoice else existing)
                summaryProvider = choiceSummary
            }
            field.kind == SudokuOptions.Kind.LIST && field.choices.isNotEmpty() -> MultiSelectListPreference(context).apply {
                val raw = SudokuOptions.raw(root, field.path)
                val selected = if (raw?.isJsonArray == true) raw.asJsonArray.map { it.asString }.toSet() else setOfNotNull(raw?.asString)
                val choices = (field.choices + selected).distinct()
                entries = choices.toTypedArray(); entryValues = choices.toTypedArray()
                setDefaultValue(selected); values = selected
                summary = if (raw == null) "" else SudokuPreferenceDisplay.text(selected.joinToString(", "))
            }
            else -> EditTextPreference(context).apply {
                bindProtocolValue(existing)
                summaryProvider = if (field.kind == SudokuOptions.Kind.SECRET) Preference.SummaryProvider<EditTextPreference> { preference ->
                    SudokuPreferenceDisplay.secret(preference.text)
                } else textSummary
                if (field.kind == SudokuOptions.Kind.SECRET) {
                    dialogLayoutResource = R.layout.layout_password_dialog
                    setDialogMessage(R.string.sudoku_key_help)
                }
                setOnBindEditTextListener { input ->
                    input.inputType = when (field.kind) {
                        SudokuOptions.Kind.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        SudokuOptions.Kind.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        SudokuOptions.Kind.LIST -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                    if (field.kind == SudokuOptions.Kind.SECRET) input.transformationMethod = PasswordTransformationMethod.getInstance()
                }
            }
        }
        pref.key = "sudoku.${field.path}"; pref.title = label; pref.isPersistent = false
        pref.isSingleLineTitle = false
        pref.setIcon(when (field.kind) {
            SudokuOptions.Kind.SECRET -> R.drawable.ic_settings_password
            SudokuOptions.Kind.BOOLEAN -> R.drawable.ic_baseline_layers_24
            else -> R.drawable.ic_action_settings
        })
        pref.setOnPreferenceChangeListener { _, value ->
            if (syncingOptions) return@setOnPreferenceChangeListener false
            if (value is Set<*>) update(field, null, value.filterIsInstance<String>().toSet())
            else {
                val text = value.toString()
                if (text == emptyChoice && field.kind == SudokuOptions.Kind.CHOICE) return@setOnPreferenceChangeListener true
                update(field, text)
            }
            true
        }
        return pref
    }
}
