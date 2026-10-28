package io.nekohasekai.sagernet.fmt

internal object NodeJsonReload {
    fun changed(sources: Map<Long, NodeJson.State>, editedId: Long? = null,
                read: (List<Long>) -> Map<Long, NodeJson.State>): Boolean {
        val ids = if (editedId == null) sources.keys.toList() else sources.keys.filter { it == editedId }
        if (ids.isEmpty()) return false
        val current = read(ids)
        return ids.any { sources[it] != current[it] }
    }
}
