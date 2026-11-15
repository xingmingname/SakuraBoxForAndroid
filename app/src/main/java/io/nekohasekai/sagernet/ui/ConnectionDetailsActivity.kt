package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.monitor.ConnectionMonitorClient
import io.nekohasekai.sagernet.ui.monitor.ConnectionMonitorState
import io.nekohasekai.sagernet.ui.monitor.monitorDuration
import io.nekohasekai.sagernet.ui.monitor.monitorEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.nekohasekai.sagernet.ui.monitor.verifiedConnectionOwnerLabel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import java.util.UUID

class ConnectionDetailsActivity : ThemedActivity(R.layout.layout_connection_details) {
    companion object { const val CONNECTION_ID = "connection_id" }
    private var client: ConnectionMonitorClient? = null
    private val fields = LinkedHashMap<Int, TextView>()
    private var applicationOwner: Pair<Int, String>? = null
    private var applicationJob: Job? = null
    private var applicationDisplay = ""
    private val labels = listOf(
        R.string.monitor_connection_state, R.string.monitor_domain, R.string.monitor_destination,
        R.string.monitor_destination_port, R.string.monitor_source, R.string.monitor_protocol,
        R.string.monitor_created_at, R.string.monitor_duration, R.string.monitor_upload,
        R.string.monitor_download, R.string.monitor_outbound, R.string.monitor_chain,
        R.string.monitor_rule, R.string.monitor_application)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.monitor_details)
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val container = findViewById<LinearLayout>(R.id.connection_fields)
        val padding = (16 * resources.displayMetrics.density).toInt()
        for (label in labels) {
            val group = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding / 2, padding, padding / 2)
            }
            group.addView(androidx.appcompat.widget.AppCompatTextView(this).apply {
                setText(label)
                setTextAppearance(this@ConnectionDetailsActivity, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
            })
            val value = TextView(this).apply {
                setTextAppearance(this@ConnectionDetailsActivity, com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1)
                setTextIsSelectable(true)
                text = getString(R.string.monitor_unknown)
            }
            group.addView(value, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(group)
            fields[label] = value
        }
        val id = intent.getStringExtra(CONNECTION_ID).orEmpty()
        if (runCatching { UUID.fromString(id) }.isFailure) { finish(); return }
        val monitor = ConnectionMonitorClient(this, id)
        client = monitor
        lifecycleScope.launch { monitor.state.collect { render(it) } }
    }

    override fun onStart() { super.onStart(); client?.start(lifecycleScope) }
    override fun onStop() { client?.stop(); applicationJob?.cancel(); applicationOwner = null; applicationDisplay = ""; super.onStop() }
    override fun onDestroy() { client?.stop(); client = null; super.onDestroy() }

    private fun render(state: ConnectionMonitorState) {
        val unknown = getString(R.string.monitor_unknown)
        val entry = if (state.available) state.connections.firstOrNull() ?: state.closed else null
        fields[R.string.monitor_connection_state]?.text = getString(when {
            state.connected == false -> R.string.monitor_disconnected
            !state.available -> R.string.monitor_unavailable
            state.closed != null -> R.string.monitor_closed
            entry == null -> R.string.monitor_unavailable
            else -> R.string.monitor_active
        })
        if (entry == null) {
            for (label in labels.drop(1)) fields[label]?.text = unknown
            applicationJob?.cancel()
            applicationOwner = null
            applicationDisplay = ""
            return
        }
        fun set(label: Int, value: String) {
            val text = value.ifBlank { unknown }
            fields[label]?.let { if (it.text.toString() != text) it.text = text }
        }
        set(R.string.monitor_domain, entry.domain)
        set(R.string.monitor_destination, entry.destination)
        set(R.string.monitor_destination_port, entry.port.takeIf { it > 0 }?.toString().orEmpty())
        set(R.string.monitor_source, monitorEndpoint(entry.source, entry.sourcePort))
        set(R.string.monitor_protocol, entry.network.uppercase(Locale.ROOT))
        set(R.string.monitor_created_at, if (entry.createdAt > 0)
            DateFormat.getDateFormat(this).format(Date(entry.createdAt)) + " " +
                DateFormat.getTimeFormat(this).format(Date(entry.createdAt)) else "")
        set(R.string.monitor_duration, monitorDuration(entry.duration))
        set(R.string.monitor_upload, Formatter.formatShortFileSize(this, entry.upload))
        set(R.string.monitor_download, Formatter.formatShortFileSize(this, entry.download))
        set(R.string.monitor_outbound, entry.outbound)
        set(R.string.monitor_chain, entry.chain.joinToString(" → "))
        set(R.string.monitor_rule, entry.rule)
        val owner = entry.ownerUid?.let { uid -> entry.packages.singleOrNull()?.let { uid to it } }
        if (owner != applicationOwner) {
            applicationOwner = owner
            applicationDisplay = ""
            applicationJob?.cancel()
            set(R.string.monitor_application, "")
            if (owner != null) applicationJob = lifecycleScope.launch {
                val display = withContext(Dispatchers.IO) {
                    verifiedConnectionOwnerLabel(packageManager, owner.first, owner.second)
                        ?.let { "$it\n${owner.second}" }
                }
                if (applicationOwner == owner) {
                    applicationDisplay = display.orEmpty()
                    set(R.string.monitor_application, applicationDisplay)
                }
            }
        } else {
            set(R.string.monitor_application, applicationDisplay)
        }
    }

}
