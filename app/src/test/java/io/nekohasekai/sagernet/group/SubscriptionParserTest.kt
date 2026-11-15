package io.nekohasekai.sagernet.group

import com.google.gson.Gson
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionParserTest {
    @Test
    fun parsesClashYamlAndKeepsOrderAndCredentials() = runBlocking {
        val yaml = """
            proxies:
              - {type: socks5, name: 首个节点, server: 127.0.0.1, port: 1081, username: local, password: "p#word"}
              - {type: socks5, name: next, server: 127.0.0.2, port: 1082}
        """.trimIndent()

        val nodes = requireNotNull(SubscriptionParser.parseRaw(yaml)).map { it as SOCKSBean }

        assertEquals(listOf("首个节点", "next"), nodes.map { it.name })
        assertEquals(listOf(1081, 1082), nodes.map { it.serverPort })
        assertEquals("p#word", nodes.first().password)
        assertEquals("", nodes.last().password)
    }

    @Test
    fun parsesWireGuardIniAndKeepsFileBasedNameAndExplicitMtu() = runBlocking {
        val node = parseWireGuardIni("MTU=1380")

        assertEquals("local", node.name)
        assertEquals("10.0.0.2/32", node.localAddress)
        assertEquals("127.0.0.1", node.serverAddress)
        assertEquals(51820, node.serverPort.toInt())
        assertMtuRoundTrip(node, 1380)
    }

    @Test
    fun missingWireGuardMtuKeepsBeanDefault() = runBlocking {
        assertMtuRoundTrip(parseWireGuardIni(), 1420)
    }

    @Test
    fun blankWireGuardMtuKeepsBeanDefault() = runBlocking {
        for (field in listOf("MTU=", "MTU=  ")) {
            assertEquals(1420, parseWireGuardIni(field).mtu.toInt())
        }
    }

    @Test
    fun invalidWireGuardMtuTextFailsImport() = runBlocking {
        assertNull(SubscriptionParser.parseRaw(wireGuardIni("MTU=invalid")))
    }

    @Test
    fun invalidWireGuardMtuNumbersFailImport() = runBlocking {
        for (value in listOf("-1", "2147483648", "1380.5")) {
            assertNull("MTU=$value", SubscriptionParser.parseRaw(wireGuardIni("MTU=$value")))
        }
    }

    @Test
    fun explicitZeroWireGuardMtuPreservesCoreDefaultSelection() = runBlocking {
        assertMtuRoundTrip(parseWireGuardIni("MTU=0"), 0)
    }

    private suspend fun parseWireGuardIni(mtuField: String = ""): WireGuardBean =
        requireNotNull(SubscriptionParser.parseRaw(wireGuardIni(mtuField), "local.conf")).single() as WireGuardBean

    private fun wireGuardIni(mtuField: String) = """
        [Interface]
        PrivateKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address=10.0.0.2/32
        $mtuField
        [Peer]
        PublicKey=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Endpoint=127.0.0.1:51820
    """.trimIndent()

    private fun assertMtuRoundTrip(node: WireGuardBean, expected: Int) {
        assertEquals(expected, node.mtu.toInt())
        val serialized = KryoConverters.serialize(node)
        val clonedBytes = KryoConverters.serialize(node.clone())
        assertArrayEquals(serialized, clonedBytes)

        val restored = KryoConverters.wireguardDeserialize(clonedBytes)
        assertArrayEquals(serialized, KryoConverters.serialize(restored))
        assertArrayEquals(serialized, KryoConverters.serialize(parseUniversal(restored.toUniversalLink())))

        val endpoint = Gson().toJsonTree(buildSingBoxEndpointWireguardBean(restored).asMap()).asJsonObject
        assertEquals(expected, endpoint["mtu"].asInt)
    }
}
