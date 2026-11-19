package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DashboardTrafficHistory
import io.nekohasekai.sagernet.databinding.LayoutConnectionItemBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionsBinding
import io.nekohasekai.sagernet.ui.monitor.ConnectionMonitorClient
import io.nekohasekai.sagernet.ui.monitor.DashboardCards
import io.nekohasekai.sagernet.ui.monitor.DashboardConnection
import io.nekohasekai.sagernet.ui.monitor.DashboardFilter
import io.nekohasekai.sagernet.ui.monitor.DashboardPresenter
import io.nekohasekai.sagernet.ui.monitor.monitorDuration
import io.nekohasekai.sagernet.ui.monitor.target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {
    private var client: ConnectionMonitorClient? = null
    private var binding: LayoutConnectionsBinding? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_connections)
        val layout = LayoutConnectionsBinding.bind(view)
        binding = layout
        val filters = MutableStateFlow(DashboardFilter())
        val days = MutableStateFlow(1)
        val cards = DashboardCards(view.context, { filters.value = it(filters.value) }, { days.value = it })
        val rows = ConnectionsAdapter { id ->
            startActivity(Intent(requireContext(), ConnectionDetailsActivity::class.java)
                .putExtra(ConnectionDetailsActivity.CONNECTION_ID, id))
        }
        layout.connectionsList.layoutManager = LinearLayoutManager(view.context)
        layout.connectionsList.itemAnimator = null
        layout.connectionsList.adapter = ConcatAdapter(CardSection(cards.header), rows, CardSection(cards.footer))
        val monitor = ConnectionMonitorClient(view.context, dashboard = true)
        client = monitor
        val presenter = DashboardPresenter(view.context)
        viewLifecycleOwner.lifecycleScope.launch {
            cards.showVersion(withContext(Dispatchers.Default) { runCatching { libcore.Libcore.versionBox() }.getOrDefault("") })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    try {
                        combine(monitor.state, filters) { state, filter ->
                            withContext(Dispatchers.IO) { presenter.frame(state, filter) }
                        }.collect { frame ->
                            cards.render(frame)
                            // Keep at most one list diff pending.
                            suspendCancellableCoroutine<Unit> { continuation ->
                                rows.submitList(frame.connections.rows) {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            }
                        }
                    } finally {
                        presenter.pause()
                    }
                }
                launch {
                    days.collectLatest { range ->
                        while (isActive) {
                            val now = System.currentTimeMillis()
                            val history = withContext(Dispatchers.IO) {
                                runCatching { DashboardTrafficHistory.read(now, range) }.getOrNull()
                            }
                            cards.renderHistory(history, range, now)
                            delay(10_000)
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        client?.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onStop() {
        client?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        client?.stop()
        client = null
        binding?.connectionsList?.adapter = null
        binding = null
        super.onDestroyView()
    }

    private class CardSection(private val content: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            content.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(content) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    private class ConnectionsAdapter(private val open: (String) -> Unit) :
        ListAdapter<DashboardConnection, ConnectionHolder>(object : DiffUtil.ItemCallback<DashboardConnection>() {
            override fun areItemsTheSame(a: DashboardConnection, b: DashboardConnection) = a.connection.id == b.connection.id
            override fun areContentsTheSame(a: DashboardConnection, b: DashboardConnection) = a == b
        }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ConnectionHolder(
            LayoutConnectionItemBinding.inflate(LayoutInflater.from(parent.context), parent, false), open)
        override fun onBindViewHolder(holder: ConnectionHolder, position: Int) = holder.bind(getItem(position))
    }

    private class ConnectionHolder(private val layout: LayoutConnectionItemBinding, open: (String) -> Unit) :
        RecyclerView.ViewHolder(layout.root) {
        private var id = ""
        init { layout.root.setOnClickListener { if (id.isNotEmpty()) open(id) } }

        fun bind(row: DashboardConnection) {
            val item = row.connection
            id = item.id
            val context = layout.root.context
            layout.connectionTarget.text = item.target(context)
            layout.connectionProtocol.text = listOfNotNull(context.getString(R.string.monitor_protocol_port,
                item.network.uppercase(java.util.Locale.ROOT),
                item.port.takeIf { it > 0 }?.toString() ?: context.getString(R.string.monitor_unknown)),
                context.getString(R.string.monitor_active), row.appName).joinToString(" · ")
            fun bytes(value: Long) = Formatter.formatShortFileSize(context, value.coerceAtLeast(0))
            layout.connectionTraffic.text = context.getString(R.string.monitor_row_traffic,
                bytes(item.upload), bytes(item.download), monitorDuration(item.duration)) +
                row.rate?.let { "  " + context.getString(R.string.dashboard_rates,
                    context.getString(R.string.dashboard_per_second, bytes(it.rx)),
                    context.getString(R.string.dashboard_per_second, bytes(it.tx))) }.orEmpty()
        }
    }
}
