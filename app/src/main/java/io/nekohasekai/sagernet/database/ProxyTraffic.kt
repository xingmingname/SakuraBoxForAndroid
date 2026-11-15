package io.nekohasekai.sagernet.database

/** Read-only Room projection; no table or schema change. */
data class ProxyTraffic(val id: Long, val rx: Long, val tx: Long)
