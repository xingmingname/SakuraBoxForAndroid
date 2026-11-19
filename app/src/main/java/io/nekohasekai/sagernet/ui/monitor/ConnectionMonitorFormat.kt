package io.nekohasekai.sagernet.ui.monitor

import android.content.Context
import io.nekohasekai.sagernet.R
import java.util.Locale

internal fun MonitoredConnection.target(context: Context): String =
    domain.ifBlank { destination }.ifBlank { context.getString(R.string.monitor_unknown) }

internal fun monitorEndpoint(host: String, port: Int): String {
    if (host.isBlank()) return ""
    val address = if (':' in host && !host.startsWith("[")) "[$host]" else host
    return if (port > 0) "$address:$port" else address
}

internal fun monitorDuration(value: Long): String {
    val seconds = value.coerceAtLeast(0) / 1000
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}
