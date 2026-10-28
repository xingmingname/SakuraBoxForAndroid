package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.ProxyEntity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.*
import org.junit.Test

class ShadowTlsUniversalTest {
    private fun shadow(version: Int) = ShadowTLSBean().apply {
        initializeDefaultValues(); this.version=version; serverAddress="example.invalid"; serverPort=443
        name="synthetic-shadowtls"; password="synthetic-password"; sni="tls.invalid"; utlsFingerprint="chrome"
        protocolOptionsJson="""{"tls":{"handshake_timeout":"5s"}}"""
        customOutboundJson="""{"type":"shadowtls","server":"127.0.0.1","server_port":443,"version":3}"""
        customConfigJson="""{"outbounds":[{"type":"direct"}]}"""; jsonMode=NodeJson.NONE
    }
    @Test fun shadowTlsCompressedAndLegacyRawSnFormatsRoundTripAllFields() {
        for (version in 1..3) {
            val bean=shadow(version)
            val current=bean.toUniversalLink()
            assertTrue(current.startsWith("sn://shadowtls?")); assertFalse(current.contains("sn://null"))
            val legacy="sn://shadowtls:"+Util.b64EncodeUrlSafe(KryoConverters.serialize(bean))
            for (link in listOf(current,legacy)) {
                val decoded=parseUniversal(link)
                assertTrue(decoded is ShadowTLSBean)
                assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(decoded))
                assertEquals(ProxyEntity.TYPE_SHADOWTLS, ProxyEntity().putBean(decoded).type)
            }
        }
    }
    @Test fun existingNativeProtocolSnTypesAndTheirPayloadsRemainCompatible() {
        val beans = listOf<AbstractBean>(
            io.nekohasekai.sagernet.fmt.socks.SOCKSBean(), io.nekohasekai.sagernet.fmt.http.HttpBean(),
            io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean(), io.nekohasekai.sagernet.fmt.v2ray.VMessBean(),
            io.nekohasekai.sagernet.fmt.trojan.TrojanBean(), io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean(),
            io.nekohasekai.sagernet.fmt.ssh.SSHBean(), io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean(),
            io.nekohasekai.sagernet.fmt.tuic.TuicBean(), moe.matsuri.nb4a.proxy.anytls.AnyTLSBean())
        for (bean in beans) {
            bean.initializeDefaultValues(); bean.serverAddress="127.0.0.1"; bean.serverPort=443
            val decoded=parseUniversal(bean.toUniversalLink())
            assertEquals(bean.javaClass, decoded.javaClass)
            assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(decoded))
        }
    }
    @Test fun typeIdentifierIsUniqueAndAllHistoricalIdentifiersAreUnchanged() {
        val historical=mapOf("socks" to ProxyEntity.TYPE_SOCKS, "http" to ProxyEntity.TYPE_HTTP,
            "ss" to ProxyEntity.TYPE_SS, "vmess" to ProxyEntity.TYPE_VMESS, "trojan" to ProxyEntity.TYPE_TROJAN,
            "trojan-go" to ProxyEntity.TYPE_TROJAN_GO, "mieru" to ProxyEntity.TYPE_MIERU, "naive" to ProxyEntity.TYPE_NAIVE,
            "hysteria" to ProxyEntity.TYPE_HYSTERIA, "ssh" to ProxyEntity.TYPE_SSH, "wg" to ProxyEntity.TYPE_WG,
            "tuic" to ProxyEntity.TYPE_TUIC, "anytls" to ProxyEntity.TYPE_ANYTLS, "neko" to ProxyEntity.TYPE_NEKO,
            "config" to ProxyEntity.TYPE_CONFIG)
        for ((name,id) in historical) { assertEquals(id, TypeMap[name]); assertEquals(name, TypeMap.reversed[id]) }
        assertEquals("shadowtls", TypeMap.reversed[ProxyEntity.TYPE_SHADOWTLS])
        assertEquals(TypeMap.size,TypeMap.values.toSet().size)
    }
}
