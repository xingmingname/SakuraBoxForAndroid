package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.internal.ChainBean

internal class ChainGraph(
    private val cycleMessage: () -> String = { "Circular proxy chain" },
    private val depthMessage: () -> String = { "Proxy chain nesting exceeds 128 levels" },
) {
    companion object {
        fun children(bean: AbstractBean?): List<Long> =
            if (bean is ChainBean && bean.jsonMode != NodeJson.OUTBOUND && bean.jsonMode != NodeJson.FULL)
                bean.proxies else emptyList()
    }

    private val path = hashSetOf<Long>()

    fun <T> within(id: Long, action: () -> T): T {
        require(id !in path) { cycleMessage() }
        require(path.size < 128) { depthMessage() }
        path.add(id)
        try { return action() } finally { path.remove(id) }
    }

    fun validateAddition(parentId: Long, candidateId: Long, children: (Long) -> List<Long>) {
        require(!hasCycle(parentId, listOf(candidateId), children)) { cycleMessage() }
    }

    fun hasCycle(parentId: Long, roots: List<Long>, children: (Long) -> List<Long>): Boolean {
        val checked = hashSetOf<Long>()
        val visiting = hashSetOf<Long>()
        val pending = java.util.ArrayDeque<Pair<Long, Boolean>>()
        roots.asReversed().forEach { pending.add(it to false) }
        while (pending.isNotEmpty()) {
            val (id, leaving) = pending.removeLast()
            if (leaving) {
                visiting.remove(id); checked.add(id)
            } else {
                if (parentId != 0L && id == parentId) return true
                if (id in checked) continue
                if (!visiting.add(id)) return true
                pending.add(id to true)
                children(id).asReversed().forEach { pending.add(it to false) }
            }
        }
        return false
    }
}
