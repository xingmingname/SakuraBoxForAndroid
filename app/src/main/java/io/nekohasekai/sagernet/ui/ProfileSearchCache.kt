package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.GroupOrder

internal class ProfileSearchCache : HashMap<Long, ProxyEntity>() {
    fun orderedIds(order: Int): List<Long> {
        val original = values.sortedWith(compareBy<ProxyEntity> { it.userOrder }.thenBy { it.id })
        val ordered = when (order) {
            GroupOrder.BY_NAME -> original.sortedBy { it.displayName() }
            GroupOrder.BY_DELAY -> original.sortedBy { if (it.status == 1) it.ping else 114514 }
            else -> original
        }
        return ordered.map { it.id }
    }

    fun matchingIds(query: String): List<Long> {
        val lower = query.lowercase()
        val result = ArrayList<Long>()
        for ((id, profile) in this) {
            if (profile.displayName().lowercase().contains(lower) ||
                profile.displayType().lowercase().contains(lower) ||
                profile.displayAddress().lowercase().contains(lower)) result.add(id)
        }
        return result
    }

    fun removeVisible(ids: MutableList<Long>, index: Int): Boolean {
        if (index !in ids.indices) return false
        remove(ids.removeAt(index))
        return true
    }

    fun updateExisting(profile: ProxyEntity): ProxyEntity? {
        val previous = get(profile.id) ?: return null
        put(profile.id, profile)
        return previous
    }
}
