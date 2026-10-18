package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class EndpointConfigImportTest {
    @Test fun preservesEndpointAndRoutingAsFullConfig() {
        val text = """{"endpoints":[{"type":"wireguard","tag":"wg"}],"route":{"final":"wg"},"outbounds":[{"type":"direct"}],"dns":{"final":"dns-direct"}}"""
        val bean = importEndpointConfig(text)!!
        assertEquals(0, bean.type.toInt())
        assertEquals(text, bean.config)
        assertNull(importEndpointConfig("""{"endpoints":[],"outbounds":[]}"""))
        assertThrows(IllegalArgumentException::class.java) { importEndpointConfig("""{"endpoints":{}}""") }
    }
}
