package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.ProtocolOptions.Kind
import io.nekohasekai.sagernet.fmt.nakis.NakisBean
import io.nekohasekai.sagernet.fmt.nakis.NakisOptions
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class NakisSettingsActivity : ProfileSettingsActivity<NakisBean>() {
    override fun createEntity() = NakisBean().applyDefaultValues()

    private val bindings = PreferenceBindingManager().apply {
        add(PreferenceBinding(Type.Text, "name"))
        add(PreferenceBinding(Type.Text, "serverAddress"))
        add(PreferenceBinding(Type.TextToInt, "serverPort"))
    }

    override fun NakisBean.init() { bindings.writeToCacheAll(this) }
    override fun NakisBean.serialize() { bindings.fromCacheAll(this) }

    private val cacheKey = ProtocolAdvancedPreferences.CACHE_KEY
    private var jsonStatus: Preference? = null
    private val optionRows = mutableListOf<Triple<Preference, String, Kind>>()
    private var renderedOptions: String? = null

    override fun onResume() {
        super.onResume()
        jsonStatus?.isVisible = NodeJson.enabled(currentEditorBean())
        val json = DataStore.profileCacheStore.getString(cacheKey).orEmpty()
        if (renderedOptions != json) {
            val root = ProtocolOptions.parse(json)
            for ((preference, key, kind) in optionRows) {
                preference.bindProtocolValue(NakisOptions.editorText(root, key, kind))
            }
            updateOptionAvailability(root)
            renderedOptions = json
        }
    }

    private fun options() = ProtocolOptions.parse(DataStore.profileCacheStore.getString(cacheKey))

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context)
        preferenceScreen = screen
        for ((id, label, icon) in listOf(
            Triple("name", R.string.profile_name, R.drawable.ic_social_emoji_symbols),
            Triple("serverAddress", R.string.server_address, R.drawable.ic_hardware_router),
            Triple("serverPort", R.string.server_port, R.drawable.ic_maps_directions_boat),
        )) {
            screen.addPreference(EditTextPreference(context).apply {
                key = id
                setTitle(label)
                setIcon(icon)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                if (id == "serverPort") setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
            })
        }
        optionRows.clear()
        renderedOptions = DataStore.profileCacheStore.getString(cacheKey).orEmpty()
        val root = ProtocolOptions.parse(renderedOptions)
        text(screen, root, "uuid", R.string.nakis_uuid, secret = true)
        jsonStatus = Preference(context).apply {
            key = "nakis.jsonPriority"
            setTitle(R.string.protocol_manual_override_priority)
            isSelectable = false
            isVisible = NodeJson.enabled(currentEditorBean())
        }.also { screen.addPreference(it) }

        val tls = PreferenceCategory(context).apply {
            key = "nakis.tls"
            setTitle(R.string.nakis_security)
        }.also { screen.addPreference(it) }
        tls.addPreference(ListPreference(context).apply {
            key = "nakis.option.tls"
            setTitle(R.string.nakis_tls)
            entries = arrayOf(getString(R.string.nakis_default), getString(R.string.nakis_on), getString(R.string.nakis_off))
            entryValues = arrayOf("", "true", "false")
            isPersistent = false
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            bindOption(this, root, "tls", Kind.BOOLEAN)
            setOnPreferenceChangeListener { _, new -> update("tls", Kind.BOOLEAN, new.toString()) }
        })
        text(tls, root, "sni", R.string.nakis_sni)
        text(tls, root, "tls_pins", R.string.nakis_tls_pins, Kind.LIST)
        text(tls, root, "ech_config", R.string.nakis_ech)

        val advanced = PreferenceCategory(context).apply {
            key = "nakis.advanced"
            setTitle(R.string.nakis_advanced)
            initialExpandedChildrenCount = 0
        }.also { screen.addPreference(it) }
        text(advanced, root, "server_alternates", R.string.nakis_alternates, Kind.LIST)
        text(advanced, root, "pool_lo", R.string.nakis_pool_lo, Kind.INTEGER)
        text(advanced, root, "pool_hi", R.string.nakis_pool_hi, Kind.INTEGER)
        text(advanced, root, "pool_max_uses", R.string.nakis_pool_max_uses, Kind.INTEGER)
        text(advanced, root, "pool_max_age", R.string.nakis_pool_max_age, Kind.DURATION)
        text(advanced, root, "race_count", R.string.nakis_race_count, Kind.INTEGER)
        text(advanced, root, "dial_timeout", R.string.nakis_dial_timeout, Kind.DURATION)
        text(advanced, root, "handshake_timeout", R.string.nakis_handshake_timeout, Kind.DURATION)
        text(advanced, root, "keepalive", R.string.nakis_keepalive, Kind.DURATION)
        text(advanced, root, "congestion", R.string.nakis_congestion)
        text(advanced, root, "send_pack", R.string.nakis_send_pack, Kind.INTEGER)
        text(advanced, root, "send_tail", R.string.nakis_send_tail, Kind.INTEGER)
        text(advanced, root, "send_quiet", R.string.nakis_send_quiet, Kind.INTEGER)
        text(advanced, root, "shape_up", R.string.nakis_shape_up, Kind.TEXT)
        text(advanced, root, "shape_wait", R.string.nakis_shape_wait, Kind.DURATION)
        text(advanced, root, "half_close_timeout", R.string.nakis_half_close_timeout, Kind.DURATION)
        text(advanced, root, "udp_jitter", R.string.nakis_udp_jitter, Kind.DURATION)
        updateOptionAvailability(root)
    }

    private fun text(parent: PreferenceGroup, root: JsonObject, id: String, @StringRes title: Int,
                     kind: Kind = Kind.TEXT, secret: Boolean = false) {
        parent.addPreference(EditTextPreference(parent.context).apply {
            key = "nakis.option.$id"
            setTitle(title)
            when (id) {
                "pool_lo", "pool_hi" -> setDialogMessage(R.string.nakis_pool_limits)
                "race_count" -> setDialogMessage(R.string.nakis_race_scope)
                "keepalive" -> setDialogMessage(R.string.nakis_keepalive_scope)
                "congestion" -> setDialogMessage(R.string.nakis_congestion_scope)
                "udp_jitter" -> setDialogMessage(R.string.nakis_udp_jitter_help)
            }
            isPersistent = false
            if (secret) dialogLayoutResource = R.layout.layout_password_dialog
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val value = it.text.orEmpty()
                if (secret && value.isNotEmpty()) "••••••••" else SudokuPreferenceDisplay.text(value)
            }
            bindOption(this, root, id, kind)
            setOnBindEditTextListener { field ->
                if (secret) {
                    field.setHorizontallyScrolling(true)
                    field.ellipsize = null
                    field.imeOptions = field.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                } else {
                    field.inputType = when {
                        kind == Kind.INTEGER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                        kind == Kind.LIST || id == "shape_up" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                }
                field.hint = NakisOptions.editorDefaults[id]
            }
            setOnPreferenceChangeListener { _, new -> update(id, kind, new.toString()) }
        })
    }

    private fun bindOption(preference: Preference, root: JsonObject, id: String, kind: Kind) {
        preference.bindProtocolValue(NakisOptions.editorText(root, id, kind))
        optionRows.add(Triple(preference, id, kind))
    }

    private fun updateOptionAvailability(root: JsonObject) {
        for ((preference, key) in optionRows) {
            preference.isEnabled = NakisOptions.isEditable(root, key)
        }
    }

    private fun update(id: String, kind: Kind, input: String): Boolean {
        val value = runCatching { NakisOptions.parseValue(id, kind, input) }.getOrElse { return false }
        val root = options()
        if (value == null) root.remove(id) else root.add(id, value)
        DataStore.profileCacheStore.putString(cacheKey, root.toString())
        updateOptionAvailability(root)
        return true
    }
}
