package io.nekohasekai.sagernet.ui.profile

import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.gson.JsonArray
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ConnectionSecurity
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.MtlsOptions
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.anyTlsEchEnabled

internal fun EditTextPreference.bindMtlsValue(value: String) {
    isPersistent = false
    bindProtocolValue(value)
    summaryProvider = Preference.SummaryProvider<EditTextPreference> {
        context.getString(if (MtlsOptions.isSet(it.text)) R.string.mtls_set else R.string.mtls_unset)
    }
}

internal fun PreferenceFragmentCompat.syncMtlsValues(raw: String?) {
    val options = try { ProtocolOptions.parse(raw) } catch (_: Exception) { return }
    for ((key, field) in listOf("mtlsClientCertificate" to MtlsOptions.certificate, "mtlsClientKey" to MtlsOptions.key)) {
        val pref = findPreference<EditTextPreference>(key) ?: continue
        val value = ProtocolOptions.text(options, field)
        if (pref.text != value) pref.bindMtlsValue(value)
    }
}

private val echEnabled = ProtocolOptions.Field("tls.ech.enabled", ProtocolOptions.Kind.BOOLEAN)
private val echConfig = ProtocolOptions.Field("tls.ech.config", ProtocolOptions.Kind.LIST)

internal fun PreferenceFragmentCompat.syncAnyTlsEchValues(raw: String?) {
    val toggle = findPreference<SwitchPreference>("enableECH")?.takeUnless { it.isPersistent } ?: return
    val options = try { ProtocolOptions.parse(raw) } catch (_: Exception) { return }
    val pem = DataStore.profileCacheStore.getString("echConfig").orEmpty()
    toggle.isChecked = anyTlsEchEnabled(options, pem)
    val field = findPreference<EditTextPreference>("echConfig") ?: return
    val text = if (ProtocolOptions.get(options, echConfig.path) == null) pem else ProtocolOptions.text(options, echConfig)
    if (field.text != text) field.bindProtocolValue(text)
}

class ConnectionSecurityPreferences(
    private val fragment: PreferenceFragmentCompat,
    private val bean: () -> AbstractBean,
) {
    private fun current(): AbstractBean = bean().apply {
        protocolOptionsJson = DataStore.profileCacheStore.getString(ProtocolAdvancedPreferences.CACHE_KEY) ?: ""
    }

    private fun storeOptions(current: AbstractBean) {
        DataStore.profileCacheStore.putString(ProtocolAdvancedPreferences.CACHE_KEY, current.protocolOptionsJson)
    }

    fun bind() {
        val picker = fragment.findPreference<ListPreference>("connectionSecurity")!!
        val modes = ConnectionSecurity.supported(current())
        val labels = fragment.resources.getStringArray(R.array.connection_security_entries)
        picker.entries = modes.map { labels[it.ordinal] }.toTypedArray()
        picker.entryValues = modes.map { it.value }.toTypedArray()
        picker.isPersistent = false
        picker.bindProtocolValue(ConnectionSecurity.mode(current()).value)
        picker.setOnPreferenceChangeListener { _, value ->
            if (!validStoredOptions()) return@setOnPreferenceChangeListener false
            val mode = modes.firstOrNull { it.value == value } ?: return@setOnPreferenceChangeListener false
            val edited = current()
            ConnectionSecurity.select(edited, mode)
            storeOptions(edited)
            if (edited is StandardV2RayBean) DataStore.profileCacheStore.putString("security", edited.security)
            updateVisibility(mode)
            true
        }
        bindRealityField("realityPubKey", "public_key", R.string.connection_reality_public_key)
        bindRealityField("realityShortId", "short_id", R.string.connection_reality_short_id)
        bindMtlsField("mtlsClientCertificate", MtlsOptions.certificate)
        bindMtlsField("mtlsClientKey", MtlsOptions.key)
        if (current() is AnyTLSBean) bindAnyTlsEch()
        fragment.findPreference<ListPreference>("utlsFingerprint")?.setOnPreferenceChangeListener { _, value ->
            val edited = current()
            if (edited is StandardV2RayBean) edited.utlsFingerprint = value.toString()
            fragment.view?.post { refresh() }
            true
        }
        updateVisibility(ConnectionSecurity.mode(current()))
    }

    fun refresh() {
        fragment.findPreference<ListPreference>("connectionSecurity")?.summaryProvider =
            ListPreference.SimpleSummaryProvider.getInstance()
    }

    private fun bindRealityField(key: String, jsonKey: String, title: Int) {
        val pref = fragment.findPreference<EditTextPreference>(key)!!
        val edited = current()
        val root = try { ProtocolOptions.parse(edited.protocolOptionsJson) } catch (_: Exception) { com.google.gson.JsonObject() }
        val value = if (jsonKey == "public_key") ConnectionSecurity.publicKey(edited, root)
            else ConnectionSecurity.shortId(edited, root)
        if (edited is StandardV2RayBean) DataStore.profileCacheStore.putString(key, value)
        pref.isPersistent = false
        pref.setTitle(title)
        pref.setDialogMessage(if (jsonKey == "public_key") R.string.connection_reality_public_key_help
            else R.string.connection_reality_short_id_help)
        pref.bindProtocolValue(value)
        pref.setOnPreferenceChangeListener { _, input ->
            if (!validStoredOptions()) return@setOnPreferenceChangeListener false
            val text = input.toString()
            val latest = current()
            var options = ProtocolOptions.parse(latest.protocolOptionsJson)
            if (latest !is StandardV2RayBean || ProtocolOptions.get(options, "tls.reality.$jsonKey") != null) {
                options = ProtocolOptions.set(options,
                    ProtocolOptions.Field("tls.reality.$jsonKey", ProtocolOptions.Kind.TEXT), text)
                latest.protocolOptionsJson = if (options.size() == 0) "" else options.toString()
                storeOptions(latest)
            }
            if (latest is StandardV2RayBean) DataStore.profileCacheStore.putString(key, text)
            true
        }
    }

    private fun validStoredOptions(): Boolean = try {
        val root = ProtocolOptions.parse(current().protocolOptionsJson)
        for (path in listOf("tls", "tls.reality", "tls.utls")) {
            val value = ProtocolOptions.get(root, path)
            require(value == null || value.isJsonObject)
        }
        val enabled = ProtocolOptions.get(root, "tls.reality.enabled")
        require(enabled == null || enabled.isJsonPrimitive && enabled.asJsonPrimitive.isBoolean)
        true
    } catch (_: Exception) {
        Toast.makeText(fragment.requireContext(), R.string.protocol_option_invalid_json, Toast.LENGTH_LONG).show()
        false
    }

    private fun bindAnyTlsEch() {
        val store = DataStore.profileCacheStore
        val key = ProtocolAdvancedPreferences.CACHE_KEY
        fragment.findPreference<SwitchPreference>("enableECH")!!.setOnPreferenceChangeListener { _, value ->
            editAnyTlsEch {
                val options = ProtocolOptions.parse(store.getString(key))
                store.putString(key, ProtocolOptions.set(options, echEnabled, value.toString()).toString())
            }
        }
        fragment.findPreference<EditTextPreference>("echConfig")!!.setOnPreferenceChangeListener { _, value ->
            editAnyTlsEch {
                val options = ProtocolOptions.parse(store.getString(key))
                val effective = ProtocolOptions.get(options, echConfig.path)
                val pem = value.toString()
                if (effective == null) {
                    store.putString("echConfig", pem)
                } else {
                    // Edit the winning imported value. An empty array prevents stale inline PEM
                    // from taking precedence over a path or DNS after the user clears it.
                    val config = JsonArray().apply { pem.lineSequence().filter { it.isNotBlank() }.forEach { add(it) } }
                    options.getAsJsonObject("tls").getAsJsonObject("ech").add("config", config)
                    store.putString(key, options.toString())
                }
            }
        }
        fragment.syncAnyTlsEchValues(store.getString(key))
    }

    private inline fun editAnyTlsEch(update: () -> Unit): Boolean = try {
        update()
        true
    } catch (_: IllegalArgumentException) {
        Toast.makeText(fragment.requireContext(), R.string.protocol_option_invalid_json, Toast.LENGTH_LONG).show()
        false
    } catch (_: com.google.gson.JsonParseException) {
        Toast.makeText(fragment.requireContext(), R.string.protocol_option_invalid_json, Toast.LENGTH_LONG).show()
        false
    }

    private fun bindMtlsField(key: String, field: ProtocolOptions.Field) {
        val pref = fragment.findPreference<EditTextPreference>(key) ?: return
        val options = try { ProtocolOptions.parse(current().protocolOptionsJson) }
            catch (_: Exception) { com.google.gson.JsonObject() }
        pref.bindMtlsValue(ProtocolOptions.text(options, field))
        pref.setDialogMessage(R.string.mtls_pem_help)
        pref.setOnBindEditTextListener { input ->
            input.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            input.isSingleLine = false
            input.minLines = 6
            input.setHorizontallyScrolling(false)
            input.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                input.imeOptions = input.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                input.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        pref.setOnPreferenceChangeListener { _, input ->
            if (!validStoredOptions()) return@setOnPreferenceChangeListener false
            try {
                val edited = current()
                val updated = ProtocolOptions.set(ProtocolOptions.parse(edited.protocolOptionsJson), field, input.toString())
                edited.protocolOptionsJson = if (updated.size() == 0) "" else updated.toString()
                storeOptions(edited)
                true
            } catch (_: Exception) {
                Toast.makeText(fragment.requireContext(), R.string.protocol_option_invalid_json, Toast.LENGTH_LONG).show()
                false
            }
        }
    }

    private fun updateVisibility(mode: ConnectionSecurity.Mode) {
        refresh()
        val active = mode != ConnectionSecurity.Mode.NONE
        val reality = mode == ConnectionSecurity.Mode.REALITY
        fun show(key: String, visible: Boolean) {
            fragment.findPreference<Preference>(key)?.isVisible = visible
        }
        show("serverSecurityCategory", active)
        show("serverTlsCamouflageCategory", active)
        show("serverECHCategory", mode == ConnectionSecurity.Mode.TLS)
        listOf("sni", "alpn", "utlsFingerprint").forEach { show(it, active) }
        listOf("certificates", "allowInsecure", "enableECH", "echConfig").forEach {
            show(it, mode == ConnectionSecurity.Mode.TLS)
        }
        listOf("realityPubKey", "realityShortId").forEach { show(it, reality) }
        listOf("mtlsClientCertificate", "mtlsClientKey").forEach {
            show(it, mode == ConnectionSecurity.Mode.TLS && MtlsOptions.supported(current()))
        }
        fragment.findPreference<ListPreference>("utlsFingerprint")?.summaryProvider =
            Preference.SummaryProvider<ListPreference> { pref ->
                if (reality && pref.value.isNullOrBlank()) "chrome (${fragment.getString(R.string.protocol_core_default)})"
                else ListPreference.SimpleSummaryProvider.getInstance().provideSummary(pref)
            }
    }
}
