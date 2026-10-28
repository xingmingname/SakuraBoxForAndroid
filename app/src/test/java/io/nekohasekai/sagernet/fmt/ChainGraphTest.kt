package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.internal.ChainBean
import org.junit.Assert.*
import org.junit.Test

class ChainGraphTest {
    private fun validate(parent: Long, root: Long, edges: Map<Long, List<Long>>) =
        ChainGraph().validateAddition(parent, root) { edges[it].orEmpty() }

    @Test fun candidateMustNotReachTheCurrentChainDirectlyOrIndirectly() {
        assertThrows(IllegalArgumentException::class.java) { validate(1, 1, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { validate(1, 2, mapOf(2L to listOf(1L))) }
        assertThrows(IllegalArgumentException::class.java) { validate(1, 2, mapOf(2L to listOf(3L), 3L to listOf(1L))) }
    }

    @Test fun preexistingCandidateCycleIsRejectedEvenWhenParentIsNew() {
        assertThrows(IllegalArgumentException::class.java) { validate(0, 2, mapOf(2L to listOf(3L), 3L to listOf(2L))) }
    }

    @Test fun sharedSubchainsAndRepeatedLeavesAreNotCycles() {
        validate(1, 2, mapOf(2L to listOf(3L, 4L, 3L), 3L to listOf(5L, 5L), 4L to listOf(3L)))
    }

    @Test fun explicitOutboundModeDoesNotTraverseDormantChainComponents() {
        val bean = ChainBean().apply { initializeDefaultValues(); proxies = listOf(1L) }
        NodeJson.set(bean, NodeJson.OUTBOUND, """{"type":"direct"}""")
        ChainGraph().validateAddition(1, 2) { id -> ChainGraph.children(bean.takeIf { id == 2L }) }
        bean.jsonMode = NodeJson.NONE
        assertThrows(IllegalArgumentException::class.java) {
            ChainGraph().validateAddition(1, 2) { id -> ChainGraph.children(bean.takeIf { id == 2L }) }
        }
    }

    @Test fun generatorGuardPreservesOriginalReversalAndDuplicates() {
        val path = ChainGraph()
        val edges = mapOf(1L to listOf(2L, 3L, 2L), 2L to listOf(4L, 5L))
        fun expand(id: Long): List<Long> = edges[id]?.let { nodes ->
            path.within(id) { nodes.flatMap(::expand).asReversed() }
        } ?: listOf(id)
        assertEquals(listOf(4L, 5L, 3L, 4L, 5L), expand(1))
        assertEquals(listOf(4L, 5L, 3L, 4L, 5L), expand(1))
    }

    @Test fun generatorGuardRejectsCyclesAndIsReleasedAfterFailure() {
        val graph = ChainGraph()
        assertThrows(IllegalArgumentException::class.java) { graph.within(1) { graph.within(2) { graph.within(1) { Unit } } } }
        assertEquals(7, graph.within(1) { graph.within(2) { 7 } })
    }

    @Test fun uiGraphValidationDoesNotRecursivelyOverflowOnLongAcyclicGraphs() {
        ChainGraph().validateAddition(0, 1) { if (it < 2000) listOf(it + 1) else emptyList() }
    }

    @Test fun excessiveDepthHasAnExplicitErrorInsteadOfStackOverflow() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            val graph = ChainGraph()
            fun nested(id: Long): Unit = graph.within(id) { nested(id + 1) }
            nested(1)
        }
        assertTrue(failure.message!!.contains("128"))
    }

    @Test fun warningDoesNotTreatRepeatedOrdinaryNodesAsRecursiveChains() {
        for (roots in listOf(listOf(2L, 3L, 2L), listOf(2L, 3L, 4L, 2L))) {
            assertFalse(ChainGraph().hasCycle(1, roots) { emptyList() })
        }
    }

    @Test fun warningDetectsDirectAndIndirectReferencesToTheEditedChain() {
        val graph = ChainGraph()
        assertTrue(graph.hasCycle(1, listOf(1L)) { emptyList() })
        assertTrue(graph.hasCycle(1, listOf(2L)) { if (it == 2L) listOf(1L) else emptyList() })
        assertTrue(graph.hasCycle(1, listOf(2L)) {
            mapOf(2L to listOf(3L), 3L to listOf(1L))[it].orEmpty()
        })
    }

    @Test fun warningAllowsFiniteMixedChainsAndSharedSubchainsInOriginalOrder() {
        val roots = listOf(2L, 7L, 3L, 2L, 7L)
        val edges = mapOf(2L to listOf(4L, 7L), 3L to listOf(4L), 4L to listOf(7L, 8L, 7L))
        val lookups = mutableMapOf<Long, Int>()
        assertFalse(ChainGraph().hasCycle(1, roots) {
            lookups[it] = lookups.getOrDefault(it, 0) + 1
            edges[it].orEmpty()
        })
        assertEquals(listOf(2L, 7L, 3L, 2L, 7L), roots)
        assertTrue(lookups.values.all { it == 1 })
    }

    @Test fun warningUsesUnsavedEditedReferencesInsteadOfStoredRootEdges() {
        val stored = mapOf(1L to listOf(9L), 2L to listOf(1L))
        assertFalse(ChainGraph().hasCycle(1, listOf(9L)) { stored[it].orEmpty() })
        assertTrue(ChainGraph().hasCycle(1, listOf(2L, 9L, 2L)) { stored[it].orEmpty() })
        assertEquals(listOf(9L), stored[1L])
    }

    @Test fun warningFindsPreexistingChildCycleForNewChainsAndResetsBetweenChecks() {
        val graph = ChainGraph()
        assertTrue(graph.hasCycle(0, listOf(2L, 7L)) {
            mapOf(2L to listOf(3L), 3L to listOf(2L))[it].orEmpty()
        })
        assertFalse(graph.hasCycle(0, listOf(7L, 7L)) { emptyList() })
        assertFalse(graph.hasCycle(1, emptyList()) { error("No child lookup expected") })
    }

    @Test fun retainingAndReloadingACyclicBeanDoesNotAuthorizeRecursiveExpansion() {
        val bean = ChainBean().apply { initializeDefaultValues(); proxies = listOf(2L, 7L, 2L) }
        val saved = bean.clone()
        assertEquals(bean.proxies, saved.proxies)
        assertTrue(ChainGraph().hasCycle(1, saved.proxies) { if (it == 2L) listOf(1L) else emptyList() })
        val path = ChainGraph()
        val edges = mapOf(1L to saved.proxies, 2L to listOf(3L), 3L to listOf(1L))
        fun expand(id: Long): List<Long> = edges[id]?.let { nodes ->
            path.within(id) { nodes.flatMap(::expand).asReversed() }
        } ?: listOf(id)
        val failure = assertThrows(IllegalArgumentException::class.java) { expand(1) }
        assertEquals("Circular proxy chain", failure.message)
        assertEquals(listOf(2L, 7L, 2L), saved.proxies)
        assertEquals(42, path.within(1) { 42 })
    }


    @Test fun completeJsonModeDoesNotWarnAboutDormantChainReferences() {
        val bean = ChainBean().apply { initializeDefaultValues(); proxies = listOf(1L) }
        NodeJson.set(bean, NodeJson.FULL, """{"outbounds":[{"type":"direct","tag":"proxy"}]}""")
        assertFalse(ChainGraph().hasCycle(1, ChainGraph.children(bean)) { error("Dormant references must not be read") })
        bean.jsonMode = NodeJson.NONE
        assertTrue(ChainGraph().hasCycle(1, ChainGraph.children(bean)) { emptyList() })
    }

}
