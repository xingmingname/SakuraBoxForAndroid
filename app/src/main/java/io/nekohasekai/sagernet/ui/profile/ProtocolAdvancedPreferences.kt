package io.nekohasekai.sagernet.ui.profile

import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

internal fun Preference.bindProtocolValue(value: String) {
    // PreferenceDataStore initializes nonpersistent controls again when they join the hierarchy.
    setDefaultValue(value)
    when (this) {
        is EditTextPreference -> text = value
        is ListPreference -> this.value = value
    }
}

/** Editor for native protocol options, separate from custom outbound JSON. */
internal class ProtocolAdvancedPreferences(
    private val fragment: PreferenceFragmentCompat,
    private val bean: () -> AbstractBean
) {
    companion object {
        const val CACHE_KEY = "protocolUiOptions"
        // Explicit R references keep localized titles reachable under Release resource shrinking.
        private val titles = mapOf(
            "authenticated_length" to R.string.protocol_authenticated_length,
            "bbr_profile" to R.string.protocol_bbr_profile,
            "cipher" to R.string.protocol_cipher,
            "client_metadata" to R.string.protocol_client_metadata,
            "client_version" to R.string.protocol_client_version,
            "connect_timeout" to R.string.protocol_connect_timeout,
            "connection_receive_window" to R.string.protocol_connection_receive_window,
            "disable_chrome_parrot" to R.string.protocol_disable_chrome_parrot,
            "disable_path_mtu_discovery" to R.string.protocol_disable_path_mtu_discovery,
            "global_padding" to R.string.protocol_global_padding,
            "heartbeat" to R.string.protocol_heartbeat,
            "hop_interval_max" to R.string.protocol_hop_interval_max,
            "host_key_algorithms" to R.string.protocol_host_key_algorithms,
            "idle_session_check_interval" to R.string.protocol_idle_session_check_interval,
            "idle_session_timeout" to R.string.protocol_idle_session_timeout,
            "idle_timeout" to R.string.protocol_idle_timeout,
            "initial_packet_size" to R.string.protocol_initial_packet_size,
            "keep_alive_period" to R.string.protocol_keep_alive_period,
            "kex_algorithm" to R.string.protocol_kex_algorithm,
            "mac" to R.string.protocol_mac,
            "max_concurrent_streams" to R.string.protocol_max_concurrent_streams,
            "min_idle_session" to R.string.protocol_min_idle_session,
            "multiplex.enabled" to R.string.protocol_multiplex_enabled,
            "multiplex.max_connections" to R.string.protocol_multiplex_max_connections,
            "multiplex.max_streams" to R.string.protocol_multiplex_max_streams,
            "multiplex.min_streams" to R.string.protocol_multiplex_min_streams,
            "multiplex.padding" to R.string.protocol_multiplex_padding,
            "multiplex.protocol" to R.string.protocol_multiplex_protocol,
            "obfs.max_packet_size" to R.string.protocol_obfs_max_packet_size,
            "obfs.min_packet_size" to R.string.protocol_obfs_min_packet_size,
            "obfs.type" to R.string.protocol_obfs_type,
            "stream_receive_window" to R.string.protocol_stream_receive_window,
            "tls.certificate_public_key_sha256" to R.string.protocol_tls_certificate_public_key_sha256,
            "tls.curve_preferences" to R.string.protocol_tls_curve_preferences,
            "tls.disable_sni" to R.string.protocol_tls_disable_sni,
            "tls.ech.config" to R.string.protocol_tls_ech_config,
            "tls.ech.enabled" to R.string.protocol_tls_ech_enabled,
            "tls.ech.query_server_name" to R.string.protocol_tls_ech_query_server_name,
            "tls.handshake_timeout" to R.string.protocol_tls_handshake_timeout,
            "tls.max_version" to R.string.protocol_tls_max_version,
            "tls.min_version" to R.string.protocol_tls_min_version,
            "transport.idle_timeout" to R.string.protocol_transport_idle_timeout,
            "transport.method" to R.string.protocol_transport_method,
            "transport.permit_without_stream" to R.string.protocol_transport_permit_without_stream,
            "transport.ping_timeout" to R.string.protocol_transport_ping_timeout,
            "udp_over_stream" to R.string.protocol_udp_over_stream,
            "udp_timeout" to R.string.protocol_udp_timeout,
            "workers" to R.string.protocol_workers
        )
    }
    private var category: PreferenceCategory? = null

    fun refresh() {
        val current = bean()
        val fields = ProtocolOptions.fields(current)
        val expandedCount = category?.initialExpandedChildrenCount ?: 0
        category?.let { fragment.preferenceScreen.removePreference(it) }
        if (fields.isEmpty() && DataStore.profileCacheStore.getString(CACHE_KEY).isNullOrBlank()) return
        val context = fragment.requireContext()
        val group = PreferenceCategory(context).apply {
            title = context.getString(R.string.protocol_advanced_options)
            key = "protocolAdvancedCategory"
            initialExpandedChildrenCount = expandedCount
        }
        category = group
        fragment.preferenceScreen.addPreference(group)
        if (ProtocolOptions.isNativeQuic(current)) group.addPreference(Preference(context).apply {
            title = context.getString(R.string.protocol_quic_static_ech_required)
            isSelectable = false
        })
        if (current is StandardV2RayBean && current.type == "grpc" && ProtocolOptions.protocol(current) in setOf("vless", "vmess", "trojan"))
            group.addPreference(Preference(context).apply {
                title = context.getString(R.string.protocol_grpc_lite_supported)
                isSelectable = false
            })
        if (!current.customOutboundJson.isNullOrBlank()) group.addPreference(Preference(context).apply {
            title = context.getString(R.string.protocol_manual_override_priority)
            isSelectable = false
        })
        group.addPreference(Preference(context).apply {
            title = context.getString(R.string.protocol_clear_options)
            summary = context.getString(R.string.protocol_clear_options_summary)
            setOnPreferenceClickListener {
                DataStore.profileCacheStore.putString(CACHE_KEY, "")
                refresh()
                true
            }
        })
        val root = try { ProtocolOptions.parse(DataStore.profileCacheStore.getString(CACHE_KEY)) } catch (_: Exception) {
            group.addPreference(Preference(context).apply {
                title = context.getString(R.string.protocol_option_invalid_json)
                isSelectable = false
            })
            return // Keep the original override intact; never discard invalid/unknown settings.
        }
        for (field in fields) {
            if (!ProtocolOptions.editableField(current, field.path)) continue
            if (field.path.startsWith("tls.") && current is StandardV2RayBean && current.security != "tls") continue
            val reality = current is StandardV2RayBean && !current.realityPubKey.isNullOrBlank() || ProtocolOptions.get(root, "tls.reality.enabled")?.asBoolean == true
            if (field.path in setOf("tls.disable_sni", "tls.certificate_public_key_sha256") && reality) continue
            if (field.path == "tls.ech.query_server_name") {
                val ech = current is StandardV2RayBean && current.enableECH == true || current is AnyTLSBean && !current.echConfig.isNullOrBlank() || ProtocolOptions.get(root, "tls.ech.enabled")?.asBoolean == true
                if (!ech || reality) continue
            }
            if (field.path.startsWith("multiplex.") && field.path != "multiplex.enabled") {
                val enabled = ProtocolOptions.get(root, "multiplex.enabled")?.asBoolean ?: (current is StandardV2RayBean && current.enableMux == true)
                if (!enabled) continue
            }
            if (field.path.startsWith("obfs.") && field.path != "obfs.type" && ProtocolOptions.get(root, "obfs.type")?.asString != "gecko") continue
            val label = context.getString(titles.getValue(field.path))
            val existing = ProtocolOptions.text(root, field)
            val pref = if (field.kind in listOf(ProtocolOptions.Kind.CHOICE, ProtocolOptions.Kind.BOOLEAN)) {
                val choices = if (field.kind == ProtocolOptions.Kind.BOOLEAN) listOf("true", "false") else field.choices
                ListPreference(context).apply {
                    entryValues = (listOf("") + choices).toTypedArray()
                    entries = (listOf(context.getString(R.string.protocol_core_default)) + choices).toTypedArray()
                    isPersistent = false
                    summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                }
            } else {
                EditTextPreference(context).apply {
                    isPersistent = false
                    dialogMessage = context.getString(if (field.path == "tls.ech.config") R.string.protocol_static_ech_help else when (field.kind) {
                        ProtocolOptions.Kind.DURATION -> R.string.protocol_duration_help
                        ProtocolOptions.Kind.PINS -> R.string.protocol_pin_help
                        ProtocolOptions.Kind.LIST -> R.string.protocol_list_help
                        else -> R.string.protocol_default_help
                    })
                    summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                    setOnBindEditTextListener { input ->
                        input.inputType = when (field.kind) {
                            ProtocolOptions.Kind.INTEGER -> InputType.TYPE_CLASS_NUMBER
                            ProtocolOptions.Kind.LIST, ProtocolOptions.Kind.PINS -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                    }
                }
            }
            pref.key = "protocolAdvanced." + field.path
            pref.title = label
            pref.bindProtocolValue(existing)
            pref.setOnPreferenceChangeListener { _, value ->
                try {
                    val updated = ProtocolOptions.set(ProtocolOptions.parse(DataStore.profileCacheStore.getString(CACHE_KEY)), field, value.toString())
                    // Cross-field validation happens before saving, so paired fields can be edited in either order.
                    DataStore.profileCacheStore.putString(CACHE_KEY, if (updated.size() == 0) "" else updated.toString())
                    if (field.path in setOf("obfs.type", "multiplex.enabled", "tls.ech.enabled")) fragment.view?.post { refresh() }
                    true
                } catch (_: Exception) {
                    Toast.makeText(context, context.getString(R.string.protocol_option_invalid, label), Toast.LENGTH_LONG).show()
                    false
                }
            }
            group.addPreference(pref)
        }
    }
}
