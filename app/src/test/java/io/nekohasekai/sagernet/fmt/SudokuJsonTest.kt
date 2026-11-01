package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class SudokuJsonTest {
    private fun config(extra: String = "", automatic: Boolean = true) = NodeJson.parse("""{
        "inbounds":[{"type":"tun","address":["172.19.0.1/30"],"auto_route":true}],
        "outbounds":[{"type":"sudoku","tag":"proxy","server":"example.invalid","server_port":443,"key":"test-only"$extra}],
        "route":{"final":"proxy","auto_detect_interface":$automatic}
    }""")

    @Test fun sudokuChecksServerAndPortInFullJson() {
        NodeJson.validateReferences(config())
        for (field in listOf("server", "server_port")) {
            val root = config()
            root.getAsJsonArray("outbounds")[0].asJsonObject.remove(field)
            val error = assertThrows(NodeJson.Invalid::class.java) { NodeJson.validateReferences(root) }
            assertEquals(NodeJson.ErrorKind.MISSING, error.kind)
        }
    }

    @Test fun sudokuRequiresPhysicalNetworkProtectionEvenAlongsideProtectedDialers() {
        // A second protected dialer must not hide an unprotected Sudoku outbound.
        val root = config(automatic = false)
        root.getAsJsonArray("outbounds").add(NodeJson.parse("""{"type":"socks","tag":"other","server":"127.0.0.1","server_port":1080,"bind_interface":"wlan0"}"""))
        val error = assertThrows(NodeJson.Invalid::class.java) { NodeJson.requireVpn(root, true, setOf("wlan0")) }
        assertEquals(NodeJson.ErrorKind.ENVIRONMENT, error.kind)
        NodeJson.requireVpn(config(), true)
        NodeJson.requireVpn(config(", \"bind_interface\":\"wlan0\"", automatic = false), true, setOf("wlan0"))
    }

    @Test fun sudokuSourceAddressCannotBypassAndroidProtectValidation() {
        val error = assertThrows(NodeJson.Invalid::class.java) {
            NodeJson.requireVpn(config(", \"inet4_bind_address\":\"192.0.2.1\""), true)
        }
        assertEquals(NodeJson.ErrorKind.ENVIRONMENT, error.kind)
    }
}
