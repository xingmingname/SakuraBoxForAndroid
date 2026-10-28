package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class NodeJsonReloadTest {
    private val ordinary = NodeJson.State(NodeJson.NONE, "", "")
    private val outbound = NodeJson.State(NodeJson.OUTBOUND, """{"type":"direct"}""", "")
    @Test fun chainAndIndirectComponentChangesUseTheRuntimeDependencySnapshot() {
        val sources = mapOf(1L to ordinary, 2L to ordinary, 3L to ordinary)
        for (id in sources.keys) assertTrue(NodeJsonReload.changed(sources, id) {
            mapOf(id to outbound)
        })
    }
    @Test fun unrelatedOrUnchangedNodesDoNotRestartVpn() {
        val sources = mapOf(1L to ordinary, 2L to outbound)
        assertFalse(NodeJsonReload.changed(sources, 9L) { error("Unrelated node must not query the database") })
        assertFalse(NodeJsonReload.changed(sources, 2L) { mapOf(2L to outbound) })
    }
    @Test fun disableAndModeSwitchAndMissingDependencyRequireReload() {
        val sources = mapOf(2L to outbound)
        for (state in listOf(ordinary, NodeJson.State(NodeJson.FULL, outbound.outbound, "{}")))
            assertTrue(NodeJsonReload.changed(sources, 2L) { mapOf(2L to state) })
        assertTrue(NodeJsonReload.changed(sources, 2L) { emptyMap() })
    }
    @Test fun startupChecksAllDependenciesAndDoesNotUseAnObsoleteEditPayload() {
        val sources = mapOf(1L to ordinary, 2L to ordinary)
        assertTrue(NodeJsonReload.changed(sources) { ids ->
            assertEquals(setOf(1L, 2L), ids.toSet()); mapOf(1L to ordinary, 2L to outbound)
        })
        assertFalse(NodeJsonReload.changed(sources) { sources })
    }
    @Test fun databaseFailureIsNotMistakenForAnUnchangedConfiguration() {
        assertThrows(IllegalStateException::class.java) {
            NodeJsonReload.changed(mapOf(1L to ordinary), 1L) { error("synthetic database failure") }
        }
    }
}
