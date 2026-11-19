package io.nekohasekai.sagernet.bg

internal fun subscriptionInitialDelay(nowSeconds: Long, entries: List<Pair<Long, Long>>): Long =
    entries.minOf { (lastUpdated, intervalMinutes) ->
        (lastUpdated + intervalMinutes.coerceAtLeast(0) * 60 - nowSeconds).coerceAtLeast(0)
    }
