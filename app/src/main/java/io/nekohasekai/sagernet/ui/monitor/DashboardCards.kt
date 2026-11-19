package io.nekohasekai.sagernet.ui.monitor

import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.TrafficTotals
import io.nekohasekai.sagernet.database.DASHBOARD_HOUR_MS
import io.nekohasekai.sagernet.database.DashboardHistory
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal class DashboardCards(
    private val context: Context,
    private val onFilter: ((DashboardFilter) -> DashboardFilter) -> Unit,
    private val onHistoryRange: (Int) -> Unit,
) {
    val header = column()
    val footer = column()
    private val status: TextView
    private val node: TextView
    private val session: TextView
    private val live: TextView
    private val chart = DashboardChart(context)
    private val historyChart = DashboardChart(context)
    private val connections: TextView
    private val navigation = ChipGroup(context)
    private val expand: Chip
    private val previous: Chip
    private val next: Chip
    private val page: TextView
    private val download: TextView
    private val upload: TextView
    private val recordedDownload: TextView
    private val recordedUpload: TextView
    private val historyCaption: TextView
    private val historySince: TextView
    private val historyAxis: TextView
    private val core: TextView
    private val coreVersion: TextView
    private val topology: TextView
    private val appRows = column()
    private val noApps: TextView
    private val percent = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 1 }
    private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private var speedMinutes = 1
    private var historyDays = 1
    private var lastFrame: DashboardFrame? = null
    private var lastApps: List<DashboardRank>? = null
    private var filter = DashboardFilter()

    init {
        card(header, R.string.dashboard_vpn).apply {
            status = text(this, strong = true)
            node = text(this)
            session = text(this, small = true)
        }
        card(header, R.string.dashboard_live).apply {
            live = text(this, strong = true)
            legend(this, chart)
            choices(this, listOf(1, 5, 30), { string(R.string.dashboard_minute, it) }) {
                speedMinutes = it
                lastFrame?.let(::updateChart)
            }
            addView(chart, LinearLayout.LayoutParams(MATCH, dp(118)))
        }
        card(header, R.string.dashboard_traffic).apply {
            val current = pair(this)
            download = metric(current, R.string.dashboard_session_rx)
            upload = metric(current, R.string.dashboard_session_tx)
            val history = pair(this)
            recordedDownload = metric(history, R.string.dashboard_history_rx)
            recordedUpload = metric(history, R.string.dashboard_history_tx)
            text(this, small = true).text = string(R.string.dashboard_scope)
        }
        card(header, R.string.dashboard_connections).apply {
            val input = TextInputLayout(context).apply { hint = string(R.string.dashboard_query) }
            val edit = TextInputEditText(input.context).apply {
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(256))
                doAfterTextChanged { value -> updateFilter { it.copy(query = value.toString(), page = 0) } }
            }
            input.addView(edit, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
            choices(this, listOf("", "tcp", "udp"), {
                if (it.isEmpty()) string(R.string.dashboard_all) else it.uppercase(java.util.Locale.ROOT)
            }) { network -> updateFilter { it.copy(network = network, page = 0) } }
            addView(MaterialCheckBox(context).apply {
                text = string(R.string.dashboard_speed_sort)
                setOnCheckedChangeListener { _, checked -> updateFilter { it.copy(bySpeed = checked, page = 0) } }
            })
            connections = text(this, small = true)
        }

        navigation.setPadding(dp(12), 0, dp(12), 0)
        footer.addView(navigation, LinearLayout.LayoutParams(MATCH, WRAP))
        expand = chip(navigation, string(R.string.dashboard_expand)) {
            updateFilter { it.copy(expanded = !it.expanded, page = 0) }
        }
        previous = chip(navigation, string(R.string.dashboard_previous)) {
            val current = lastFrame?.connections?.page ?: 0
            updateFilter { it.copy(page = (current - 1).coerceAtLeast(0)) }
        }
        next = chip(navigation, string(R.string.dashboard_next)) {
            val current = lastFrame?.connections?.page ?: 0
            updateFilter { it.copy(page = current + 1) }
        }
        page = text(footer, small = true).apply { setPadding(dp(16), 0, dp(16), dp(4)) }

        card(footer, R.string.dashboard_history).apply {
            legend(this, historyChart)
            choices(this, listOf(1, 7, 30), {
                if (it == 1) string(R.string.dashboard_today) else string(R.string.dashboard_days, it)
            }, ::selectHistory)
            historyCaption = text(this, small = true)
            addView(historyChart, LinearLayout.LayoutParams(MATCH, dp(128)))
            historyAxis = text(this, small = true)
            historySince = text(this, small = true)
            text(this, small = true).text = string(R.string.dashboard_history_scope)
        }
        card(footer, R.string.dashboard_apps).apply {
            text(this, small = true).text = string(R.string.dashboard_apps_scope)
            noApps = text(this, small = true).apply { text = string(R.string.dashboard_no_apps) }
            addView(appRows, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        card(footer, R.string.dashboard_core).apply {
            core = text(this)
            coreVersion = text(this, small = true).apply {
                setTextIsSelectable(true)
                text = string(R.string.monitor_unavailable)
            }
            text(this, small = true).text = string(R.string.monitor_memory_scope)
        }
        card(footer, R.string.dashboard_topology).apply {
            text(this, small = true).text = string(R.string.dashboard_topology_scope)
            topology = text(this)
        }
    }

    fun render(frame: DashboardFrame) {
        lastFrame = frame
        val monitor = frame.monitor
        val value = monitor.dashboard
        val state = when (value?.state) {
            BaseService.State.Connected.ordinal -> R.string.dashboard_connected
            BaseService.State.Connecting.ordinal -> R.string.dashboard_connecting
            BaseService.State.Stopping.ordinal -> R.string.dashboard_stopping
            BaseService.State.Stopped.ordinal -> R.string.monitor_disconnected
            else -> if (monitor.connected == false) R.string.monitor_disconnected else R.string.monitor_unavailable
        }
        val statusText = string(state)
        val running = state == R.string.dashboard_connected && value != null && value.started > 0
        val unknown = string(R.string.monitor_unavailable)
        val count = monitor.count?.takeIf { running && monitor.available }?.toString()
            ?: if (state == R.string.monitor_disconnected) "0" else unknown
        val duration = if (running) monitorDuration(SystemClock.elapsedRealtime() - requireNotNull(value).started) else unknown
        status.update(if (running && value?.tun == false) string(R.string.dashboard_proxy_only) else statusText)
        node.isVisible = running
        node.update(if (running && !value?.name.isNullOrBlank())
            string(R.string.dashboard_node, value?.name.orEmpty(), value?.protocol.orEmpty())
            else string(R.string.monitor_unknown))
        session.isVisible = running
        session.update(if (running) string(R.string.dashboard_status_summary,
            string(if (value?.tun == true) R.string.dashboard_tun else R.string.dashboard_proxy_only),
            duration, string(R.string.monitor_active_count) + " " + count) else "")

        live.update(frame.rate?.let { string(R.string.dashboard_rates, bytesPerSecond(it.rx), bytesPerSecond(it.tx)) }
            ?: string(if (running && value?.tun == true) R.string.dashboard_rates_unavailable else R.string.monitor_unavailable))
        updateChart(frame)
        download.update(value?.bytes?.rx?.takeIf { running }?.let(::bytes) ?: unknown)
        upload.update(value?.bytes?.tx?.takeIf { running }?.let(::bytes) ?: unknown)
        val listing = frame.connections
        connections.update(when {
            state == R.string.monitor_disconnected -> statusText
            !monitor.available -> unknown
            monitor.connections.isEmpty() -> string(R.string.monitor_no_connections)
            listing.total == 0 -> string(R.string.dashboard_no_matches)
            else -> string(R.string.dashboard_matching, listing.total)
        })
        expand.isVisible = listing.total > 5 || filter.expanded
        expand.update(if (filter.expanded) string(R.string.dashboard_collapse) else string(R.string.dashboard_expand))
        previous.isVisible = filter.expanded && listing.pages > 1
        next.isVisible = previous.isVisible
        previous.isEnabled = listing.page > 0
        next.isEnabled = listing.page + 1 < listing.pages
        page.isVisible = previous.isVisible
        page.update(string(R.string.dashboard_page, listing.page + 1, listing.pages))

        val resident = monitor.memory?.takeIf { running && monitor.available }?.let(::bytes) ?: unknown
        val coreState = if (running) "$statusText · $duration" else statusText
        core.update(string(R.string.dashboard_core_summary, coreState, count, resident))
        topology.update(if (running && !value?.hops.isNullOrEmpty()) {
            val route = requireNotNull(value)
            (listOf(string(R.string.dashboard_device)) + route.hops).joinToString("\n↓\n") +
                if (route.hopCount > route.hops.size) "\n" + string(R.string.dashboard_more_hops, route.hopCount - route.hops.size) else ""
        } else string(if (running) R.string.dashboard_no_topology else R.string.monitor_unavailable))
        renderApps(frame.apps)
    }

    private fun renderApps(apps: List<DashboardRank>) {
        if (lastApps == apps) return
        lastApps = apps
        noApps.isVisible = apps.isEmpty()
        appRows.removeAllViews()
        for (item in apps) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            if (item.icon != null) {
                row.addView(ImageView(context).apply {
                    setImageDrawable(item.icon.constantState?.newDrawable(resources)?.mutate() ?: item.icon)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
            }
            val details = column()
            row.addView(details, LinearLayout.LayoutParams(0, WRAP, 1f))
            text(details, strong = true).text = item.name
            text(details, small = true).text = string(R.string.dashboard_rank_bytes, bytes(item.bytes.rx),
                bytes(item.bytes.tx), item.fraction?.let(percent::format) ?: string(R.string.monitor_unknown))
            appRows.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    fun renderHistory(data: DashboardHistory?, days: Int, now: Long) {
        recordedDownload.update(data?.total?.rx?.let(::bytes) ?: string(R.string.monitor_unavailable))
        recordedUpload.update(data?.total?.tx?.let(::bytes) ?: string(R.string.monitor_unavailable))
        historySince.isVisible = data?.firstHour != null
        historySince.update(data?.firstHour?.let {
            string(R.string.dashboard_history_since, utcDate(it * DASHBOARD_HOUR_MS).format(date))
        }.orEmpty())
        val buckets = data?.buckets.orEmpty()
        historyChart.isVisible = buckets.any { it != null }
        historyChart.showHistory(buckets)
        val interval = buckets.filterNotNull().fold(TrafficTotals()) { total, bytes -> total + bytes }
        historyCaption.update(when {
            data == null -> string(R.string.monitor_unavailable)
            buckets.all { it == null } -> string(R.string.dashboard_no_history)
            else -> string(R.string.dashboard_rates, bytes(interval.rx), bytes(interval.tx))
        })
        val today = utcDate(now)
        historyAxis.update(if (days == 1) string(R.string.dashboard_today_axis)
            else "${today.minusDays(days - 1L).format(date)} – ${today.format(date)} UTC")
        historyChart.contentDescription = historyCaption.text
    }

    fun showVersion(version: String) { coreVersion.update(version.ifBlank { string(R.string.monitor_unavailable) }) }

    private fun selectHistory(days: Int) {
        if (historyDays == days) return
        historyDays = days
        historyCaption.text = string(R.string.monitor_unavailable)
        historyAxis.text = ""
        historyChart.showHistory(emptyList())
        onHistoryRange(days)
    }

    private fun updateChart(frame: DashboardFrame) {
        chart.isVisible = frame.points.isNotEmpty()
        chart.showSpeed(frame.points, frame.monitor.dashboard?.sampledAt ?: 0, speedMinutes * 60_000L)
        chart.contentDescription = live.text
    }

    private fun updateFilter(transform: (DashboardFilter) -> DashboardFilter) {
        filter = transform(filter)
        onFilter(transform)
    }

    private fun utcDate(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
    private fun bytes(value: Long) = Formatter.formatShortFileSize(context, value)
    private fun bytesPerSecond(value: Long) = string(R.string.dashboard_per_second, bytes(value))
    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value + 0.5f).toInt()
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun pair(parent: LinearLayout) = LinearLayout(context).also {
        it.orientation = LinearLayout.HORIZONTAL
        it.isBaselineAligned = false
        parent.addView(it, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun card(parent: LinearLayout, label: Int): LinearLayout {
        val card = MaterialCardView(context).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
                com.google.android.material.color.MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.GRAY), 30)
        }
        parent.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(6), dp(12), dp(6))
        })
        val content = column().apply { setPadding(dp(14), dp(11), dp(14), dp(11)) }
        card.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        text(content, strong = true).apply {
            text = string(label)
            setPadding(0, 0, 0, dp(5))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }
        return content
    }

    private fun metric(parent: LinearLayout, label: Int): TextView {
        val tile = MaterialCardView(context).apply {
            radius = dp(8).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.layer(
                com.google.android.material.color.MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorSurface, android.graphics.Color.TRANSPARENT),
                com.google.android.material.color.MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.GRAY), 0.045f))
        }
        parent.addView(tile, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        val content = column().apply { setPadding(dp(7), dp(6), dp(7), dp(6)) }
        tile.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        text(content, small = true).text = string(label)
        return text(content, strong = true).apply { text = string(R.string.monitor_unavailable) }
    }

    private fun text(parent: LinearLayout, strong: Boolean = false, small: Boolean = false) =
        TextView(context).apply {
            TextViewCompat.setTextAppearance(this, when {
                strong -> com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1
                small -> com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption
                else -> com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
            })
            parent.addView(this, LinearLayout.LayoutParams(MATCH, WRAP))
        }

    private fun legend(parent: LinearLayout, chart: DashboardChart) {
        val row = pair(parent)
        val legendLabels = listOf(R.string.dashboard_color_rx to chart.downloadColor, R.string.dashboard_color_tx to chart.uploadColor)
        for ((label, color) in legendLabels) {
            row.addView(TextView(context).apply {
                text = string(label)
                TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                setTextColor(color)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun chip(group: ChipGroup, label: String, action: () -> Unit) = Chip(context).apply {
        id = View.generateViewId()
        text = label
        group.addView(this)
        setOnClickListener { action() }
    }

    private fun <T> choices(parent: LinearLayout, values: List<T>, label: (T) -> String, selected: (T) -> Unit) {
        val group = ChipGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
        parent.addView(group, LinearLayout.LayoutParams(MATCH, WRAP))
        values.forEachIndexed { index, value ->
            chip(group, label(value)) { selected(value) }.apply {
                isCheckable = true
                if (index == 0) isChecked = true
            }
        }
    }

    private fun TextView.update(value: String) { if (text.toString() != value) text = value }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
