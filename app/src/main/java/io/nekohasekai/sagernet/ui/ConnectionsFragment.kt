package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutConnectionsBinding
import io.nekohasekai.sagernet.databinding.LayoutConnectionItemBinding
import io.nekohasekai.sagernet.ui.monitor.ConnectionMonitorClient
import io.nekohasekai.sagernet.ui.monitor.ConnectionMonitorState
import io.nekohasekai.sagernet.ui.monitor.MonitoredConnection
import io.nekohasekai.sagernet.ui.monitor.monitorDuration
import io.nekohasekai.sagernet.ui.monitor.target
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class ConnectionsFragment : ToolbarFragment(R.layout.layout_connections) {
    private var binding: LayoutConnectionsBinding? = null
    private var client: ConnectionMonitorClient? = null
    private var adapter: ConnectionsAdapter? = null
    private var anchorId: String? = null
    private var anchorOffset = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_connections)
        val layout = LayoutConnectionsBinding.bind(view)
        binding = layout
        val rows = ConnectionsAdapter { id ->
            startActivity(Intent(requireContext(), ConnectionDetailsActivity::class.java)
                .putExtra(ConnectionDetailsActivity.CONNECTION_ID, id))
        }
        rows.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        adapter = rows
        layout.connectionsList.layoutManager = LinearLayoutManager(requireContext())
        layout.connectionsList.adapter = rows
        (layout.connectionsList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        val monitor = ConnectionMonitorClient(requireContext())
        client = monitor
        viewLifecycleOwner.lifecycleScope.launch {
            monitor.state.collect { state -> render(state) }
        }
    }

    override fun onStart() {
        super.onStart()
        client?.start(viewLifecycleOwner.lifecycleScope)
    }

    override fun onStop() {
        val list = binding?.connectionsList
        val manager = list?.layoutManager as? LinearLayoutManager
        val position = manager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
        adapter?.currentList?.getOrNull(position)?.let {
            anchorId = it.id
            anchorOffset = (manager?.findViewByPosition(position)?.top ?: 0) - (list?.paddingTop ?: 0)
        }
        client?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        client?.stop()
        client = null
        binding?.connectionsList?.adapter = null
        adapter = null
        binding = null
        super.onDestroyView()
    }

    private suspend fun render(state: ConnectionMonitorState) {
        val layout = binding ?: return
        val unavailable = getString(if (state.connected == false) R.string.monitor_disconnected else R.string.monitor_unavailable)
        layout.connectionCount.text = if (state.available) state.count?.toString() ?: unavailable else unavailable
        layout.connectionMemory.text = if (state.available) state.memory?.let {
            Formatter.formatShortFileSize(requireContext(), it)
        } ?: getString(R.string.monitor_unavailable) else unavailable
        layout.connectionsEmpty.isVisible = state.connections.isEmpty()
        layout.connectionsEmpty.text = when {
            state.connected == false -> getString(R.string.monitor_disconnected)
            !state.available -> getString(R.string.monitor_unavailable)
            else -> getString(R.string.monitor_no_connections)
        }
        val rows = adapter ?: return
        // One diff in flight; StateFlow retains only the latest pending state.
        suspendCancellableCoroutine<Unit> { continuation ->
            rows.submitList(state.connections) {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        if (state.available && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            anchorId?.let { id ->
                val index = state.connections.indexOfFirst { it.id == id }
                if (index >= 0) (layout.connectionsList.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(index, anchorOffset)
            }
            anchorId = null
        }
    }

    private class ConnectionsAdapter(private val open: (String) -> Unit) :
        ListAdapter<MonitoredConnection, ConnectionHolder>(object : DiffUtil.ItemCallback<MonitoredConnection>() {
            override fun areItemsTheSame(a: MonitoredConnection, b: MonitoredConnection) = a.id == b.id
            override fun areContentsTheSame(a: MonitoredConnection, b: MonitoredConnection) = a == b
            override fun getChangePayload(a: MonitoredConnection, b: MonitoredConnection): Any? =
                if (a.copy(upload = b.upload, download = b.download, duration = b.duration) == b) true else null
        }) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ConnectionHolder(
            LayoutConnectionItemBinding.inflate(LayoutInflater.from(parent.context), parent, false), open)
        override fun onBindViewHolder(holder: ConnectionHolder, position: Int) = holder.bind(getItem(position), false)
        override fun onBindViewHolder(holder: ConnectionHolder, position: Int, payloads: MutableList<Any>) =
            holder.bind(getItem(position), payloads.isNotEmpty())
    }

    private class ConnectionHolder(private val layout: LayoutConnectionItemBinding, open: (String) -> Unit) :
        RecyclerView.ViewHolder(layout.root) {
        private var id = ""
        init { layout.root.setOnClickListener { if (id.isNotEmpty()) open(id) } }
        fun bind(item: MonitoredConnection, countersOnly: Boolean) {
            id = item.id
            val context = layout.root.context
            if (!countersOnly) {
                layout.connectionTarget.text = item.target(context)
                layout.connectionProtocol.text = context.getString(
                    R.string.monitor_protocol_port,
                    item.network.uppercase(java.util.Locale.ROOT),
                    item.port.takeIf { it > 0 }?.toString() ?: context.getString(R.string.monitor_unknown))
            }
            layout.connectionTraffic.text = context.getString(
                R.string.monitor_row_traffic,
                Formatter.formatShortFileSize(context, item.upload),
                Formatter.formatShortFileSize(context, item.download),
                monitorDuration(item.duration))
        }
    }
}
