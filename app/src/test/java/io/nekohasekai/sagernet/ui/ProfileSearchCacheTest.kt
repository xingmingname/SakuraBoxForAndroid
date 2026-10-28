package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.junit.Assert.*
import org.junit.Test

class ProfileSearchCacheTest {
    private fun node(id: Long, name: String = "node-$id") = ProxyEntity(id = id).putBean(VMessBean().apply {
        alterId = -1; initializeDefaultValues(); this.name = name
        serverAddress = "node-$id.invalid"; serverPort = 443
    })

    @Test fun removedNodeStaysOutOfSearchUntilUndoRestoresIt() {
        val cache = ProfileSearchCache().apply { put(1, node(1)); put(2, node(2)) }
        val visible = cache.matchingIds("node").toMutableList()
        val index = visible.indexOf(1)
        val original = cache.getValue(1)
        assertTrue(cache.removeVisible(visible, index))
        assertEquals(listOf(2L), cache.matchingIds("node"))
        assertNull(cache.updateExisting(node(1, "late-update")))
        assertFalse(cache.matchingIds("late-update").contains(1L))
        cache[1] = original; visible.add(index, 1)
        assertEquals(setOf(1L, 2L), cache.matchingIds("node").toSet())
        assertEquals(2, visible.size)
    }

    @Test fun hiddenNodeUpdatesAndDeletesAreReflectedByTheNextQuery() {
        val cache = ProfileSearchCache().apply { put(1, node(1, "before")); put(2, node(2, "visible")) }
        assertEquals(listOf(2L), cache.matchingIds("visible"))
        val previous = cache.updateExisting(node(1, "after"))
        assertEquals("before", previous!!.displayName())
        assertEquals(listOf(1L), cache.matchingIds("after"))
        assertTrue(cache.matchingIds("before").isEmpty())
        cache.remove(1)
        assertTrue(cache.matchingIds("after").isEmpty())
    }

    @Test fun invalidRemovalDoesNotChangeAnyNode() {
        val cache = ProfileSearchCache().apply { put(1, node(1)) }
        val ids = mutableListOf(1L)
        for (index in listOf(-1, 1, Int.MAX_VALUE)) assertFalse(cache.removeVisible(ids, index))
        assertEquals(listOf(1L), ids); assertEquals(setOf(1L), cache.keys)
    }

    @Test fun clearingSearchKeepsPendingDeletionHiddenAndRestoresOriginalSortOnUndo() {
        val cache = ProfileSearchCache().apply {
            put(1, node(1, "beta").apply { userOrder=2; status=1; ping=40 })
            put(2, node(2, "alpha").apply { userOrder=1; status=1; ping=30 })
            put(3, node(3, "beta").apply { userOrder=3; status=0; ping=0 })
        }
        assertEquals(listOf(2L,1L,3L),cache.orderedIds(GroupOrder.ORIGIN))
        assertEquals(listOf(2L,1L,3L),cache.orderedIds(GroupOrder.BY_NAME))
        assertEquals(listOf(2L,1L,3L),cache.orderedIds(GroupOrder.BY_DELAY))
        val old=cache.getValue(2)
        val visible=cache.orderedIds(GroupOrder.ORIGIN).toMutableList()
        cache.removeVisible(visible,visible.indexOf(2))
        for (sort in listOf(GroupOrder.ORIGIN,GroupOrder.BY_NAME,GroupOrder.BY_DELAY)) {
            assertEquals(listOf(1L,3L),cache.orderedIds(sort))
        }
        cache[2]=old
        assertEquals(listOf(2L,1L,3L),cache.orderedIds(GroupOrder.ORIGIN))
    }

    @Test fun searchMatchesLegacyNameProtocolAndAddressSemanticsAtEveryListSize() {
        for (n in listOf(10, 100, 500, 1000)) {
            val cache = ProfileSearchCache().apply { for (id in 1L..n.toLong()) put(id, node(id, if (id == 1L) "节点 Alpha" else "node-$id")) }
            for (query in listOf("NODE", "vless", ".invalid:443", "节点", "absent", "")) {
                val lower = query.lowercase()
                val legacy = cache.filter { (_, p) -> p.displayName().lowercase().contains(lower) ||
                    p.displayType().lowercase().contains(lower) || p.displayAddress().lowercase().contains(lower) }.keys.toList()
                assertEquals("$n / $query", legacy, cache.matchingIds(query))
            }
        }
    }
}
