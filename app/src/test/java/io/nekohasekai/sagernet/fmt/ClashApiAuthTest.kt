package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import moe.matsuri.nb4a.utils.ClashApiSecretStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors

class ClashApiAuthTest {
    private val enabled = """{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","external_ui":"../files/yacd"}},"outbounds":[{"type":"direct"}]}"""

    @Test fun enabledApiGetsRandomCapabilityAndExplicitSecretIsPreserved() {
        val tokens = (1..32).map {
            val config = JsonParser.parseString(secureClashApiConfig(enabled)).asJsonObject
            val api = config.getAsJsonObject("experimental").getAsJsonObject("clash_api")
            assertEquals("127.0.0.1:9090", api["external_controller"].asString)
            api["secret"].asString.also { value -> assertTrue(value.matches(Regex("[0-9a-f]{64}"))) }
        }
        assertEquals(32, tokens.toSet().size)
        System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
            java.io.File(directory).mkdirs()
            java.io.File(directory, "auth").mkdirs()
            java.io.File(directory, "auth/clash-api-secure.json").writeText(secureClashApiConfig(enabled))
        }

        val custom = enabled.replace("\"external_ui\"", "\"secret\":\"synthetic-explicit\",\"external_ui\"")
        assertEquals("synthetic-explicit", JsonParser.parseString(secureClashApiConfig(custom)).asJsonObject
            .getAsJsonObject("experimental").getAsJsonObject("clash_api")["secret"].asString)
    }

    @Test fun privateStoreMigratesRotatesAndExportsDoNotOverwriteActiveSession() {
        val directory = Files.createTempDirectory("clash-auth-test").toFile()
        try {
            val store = ClashApiSecretStore(directory)
            assertThrows(java.io.IOException::class.java) { store.active() }
            secureClashApiConfig(enabled, store::activate)
            val first = store.active().secret
            val origin = store.active().panelOrigin
            assertTrue(ClashApiSecretStore.isPanelOrigin(origin))
            repeat(3) { secureClashApiConfig(enabled) } // export/URL Test has no writer
            assertEquals(first, ClashApiSecretStore(directory).active().secret)
            secureClashApiConfig(enabled, store::activate)
            assertNotEquals(first, store.active().secret)
            assertEquals(origin, store.active().panelOrigin)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val values = (1..16).map { pool.submit<String> { ClashApiSecretStore(directory).active().secret } }.map { it.get() }
                assertTrue(values.all { it == store.active().secret })
            } finally { pool.shutdownNow() }
            // Maximum explicit secret survives HTML-safe JSON expansion in the
            // private store; credentials are never included in assertion text.
            store.activate("127.0.0.1:9090", "<".repeat(4096))
            assertTrue(store.active().secret == "<".repeat(4096))
            secureClashApiConfig("""{"outbounds":[{"type":"direct"}]}""", store::activate)
            assertThrows(java.io.IOException::class.java) { store.active() }
        } finally { directory.deleteRecursively() }
    }

    @Test fun disabledConfigDoesNotCreateAnApiAndFailuresDoNotEchoCredentials() {
        val input = """{"route":{"auto_detect_interface":true},"outbounds":[{"type":"direct"}]}"""
        assertEquals(input, secureClashApiConfig(input))
        for (inputBad in listOf("""{"experimental":{"clash_api":["synthetic-sensitive"]}}""",
            """{"experimental":{"clash_api":{"external_controller":"127.0.0.1:9090","secret":{"key":"synthetic-sensitive"}}}}""")) {
            val error = assertThrows(IllegalArgumentException::class.java) { secureClashApiConfig(inputBad) }
            assertFalse(error.message.orEmpty().contains("synthetic-sensitive"))
        }
    }
}
