package moe.matsuri.nb4a.net

import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TestPluginMappingTest {
    private val config = """{"inbounds":[{"type":"direct","listen":"127.0.0.1","listen_port":12345,"override_address":"node.invalid","override_port":443}]}"""

    @Test fun nativeNodesNeedNoPluginMapping() {
        requireTestPluginMappings("{}", emptyList())
    }

    @Test fun coreLoopbackMappingIsPreserved() {
        requireTestPluginMappings(config, listOf("127.0.0.1" to 12345))
    }

    @Test fun externalPluginCannotSilentlyUseUncontrolledNetwork() {
        for (endpoint in listOf("node.invalid" to 443, "127.0.0.1" to 12346)) {
            try {
                requireTestPluginMappings(config, listOf(endpoint))
                fail("Uncontrolled plugin endpoint accepted")
            } catch (_: IOException) { }
        }
    }
}
