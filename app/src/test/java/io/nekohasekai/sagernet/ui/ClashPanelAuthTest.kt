package io.nekohasekai.sagernet.ui

import moe.matsuri.nb4a.utils.ClashApiSecretStore
import org.junit.Assert.*
import org.junit.Test

class ClashPanelAuthTest {
    @Test fun trustedLocalPanelUsesExistingYacdStorageProtocolWithoutUrlSecrets() {
        val credentials = ClashApiSecretStore.Credentials("127.0.0.1:9090", "synthetic-only")
        val html = clashPanelBootstrap("http://127.0.0.1:9090/ui", credentials)
        assertTrue(html.contains("yacd.metacubex.one"))
        assertTrue(html.contains("selectedClashAPIConfigIndex=0"))
        assertTrue(html.contains(credentials.panelOrigin + "/ui/"))
        assertFalse(Regex("[?&](secret|token)=").containsMatchIn(html))
        assertFalse(html.contains("addJavascriptInterface"))
        assertTrue(html.contains("state.clashAPIConfigs"))
        assertTrue(html.contains("worker-src 'none'"))
        assertTrue(ClashApiSecretStore.isPanelOrigin(credentials.panelOrigin))
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "clash-panel-bootstrap.html").writeText(html)
            java.io.File(directory, "clash-panel-origin.txt").writeText(credentials.panelOrigin)
            java.io.File(directory, "clash-panel-csp.txt").writeText(CLASH_PANEL_CSP)
        }
    }

    @Test fun remotePanelsAndBackendOverridesCannotReceiveNativeCapability() {
        val credentials = ClashApiSecretStore.Credentials("127.0.0.1:9090", "synthetic-only")
        for (url in listOf("https://example.invalid/ui/", "http://127.0.0.1:9999/ui/",
            "http://127.0.0.1:9090/ui/?hostname=evil.invalid", "http://127.0.0.1:9090/ui/?secret=other",
            "http://127.0.0.1:9090/ui/?%68ostname=evil.invalid", "http://127.0.0.1:9090/ui/?%73ecret=other",
            "http://user@127.0.0.1:9090/ui/", "http://127.0.0.1:9090/other")) {
            assertThrows(IllegalArgumentException::class.java) { clashPanelBootstrap(url, credentials) }
        }
    }

    @Test fun explicitSecretsCannotBreakOutOfBootstrapScript() {
        val html = clashPanelBootstrap("http://127.0.0.1:9090/ui/", ClashApiSecretStore.Credentials("127.0.0.1:9090", "</script><script>alert(1)</script>"))
        assertEquals(1, Regex("</script>").findAll(html).count())
        assertFalse(html.contains("<script>alert"))
    }
}
