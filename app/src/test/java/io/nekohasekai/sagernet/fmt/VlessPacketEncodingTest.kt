package io.nekohasekai.sagernet.fmt

import com.google.gson.Gson
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VlessPacketEncodingTest {
    private fun node() = VMessBean().apply {
        alterId = -1; initializeDefaultValues()
        uuid = "00000000-0000-4000-8000-000000000001"
        serverAddress = "127.0.0.1"; serverPort = 443; sni = "example.invalid"
        realityPubKey = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc"
        realityShortId = "00"
    }

    private fun imported(link: String) = node().apply {
        realityPubKey = ""; realityShortId = ""
        parseDuckSoft(link.replace("vless://", "https://").toHttpUrl())
        initializeDefaultValues()
    }

    @Test fun standardSharingRoundTripsEveryEncodingAndSecurityMode() {
        for (mode in ConnectionSecurity.Mode.entries) for (encoding in 0..2) {
            val original = node().apply { packetEncoding = encoding }
            ConnectionSecurity.select(original, mode)
            val link = ProxyEntity().putBean(original).toStdLink()
            assertTrue(link.startsWith("vless://"))
            val restored = imported(link).clone()
            assertEquals(original.packetEncoding, restored.packetEncoding)
            assertEquals(mode, ConnectionSecurity.mode(restored))
            val reopened = restored.clone()
            assertEquals(encoding, reopened.packetEncoding.toInt())
            assertEquals(original.uuid, reopened.uuid)
            assertEquals(restored.sni, reopened.sni)
            val outbound = buildSingBoxOutboundStandardV2RayBean(reopened, false)
            ProtocolOptions.applyTo(outbound, reopened)
            assertEquals(listOf("", "packetaddr", "xudp")[encoding], outbound.asMap()["packet_encoding"])
            System.getenv("NEKO_CONFIG_FIXTURES")?.let { directory ->
                outbound._hack_config_map["tag"] = "proxy"
                val root = SingBoxOptions.MyOptions().apply { outbounds = listOf(outbound) }
                File(directory).mkdirs()
                File(directory, "ui-regression-${mode.value}-$encoding.json").writeText(Gson().toJson(root.asMap()))
            }
        }
    }

    @Test fun canonicalAndHistoricalPacketAliasesHaveIdenticalMeaning() {
        for ((parameter, encoding) in listOf("packet" to 1, "packetaddr" to 1, "xudp" to 2)) {
            val bean = imported("vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443?packetEncoding=$parameter")
            assertEquals(encoding, bean.packetEncoding.toInt())
            assertEquals(encoding, bean.clone().packetEncoding.toInt())
        }
    }

    @Test fun unsetAndDisabledRemainExplicitlyDisabledInNativeJson() {
        for (query in listOf("", "?packetEncoding=", "?packetEncoding=none")) {
            val bean = imported("vless://00000000-0000-4000-8000-000000000001@127.0.0.1:443$query")
            assertEquals(0, bean.packetEncoding.toInt())
            val map = buildSingBoxOutboundStandardV2RayBean(bean, false).asMap()
            assertTrue(map.containsKey("packet_encoding"))
            assertEquals("", map["packet_encoding"])
            assertNotEquals("none", map["packet_encoding"])
        }
    }
}
