package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.ProtocolOptions.Field
import io.nekohasekai.sagernet.fmt.ProtocolOptions.Kind
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean

/** Advanced editor fields and capability checks for native outbound profiles. */
internal object ProtocolOptionFields {
    private fun duration(p: String) = Field(p, Kind.DURATION)
    private fun integer(p: String, min: Long = 0, max: Long = Int.MAX_VALUE.toLong()) = Field(p, Kind.INTEGER, min, max)
    private fun bool(p: String) = Field(p, Kind.BOOLEAN)
    private fun choice(p: String, vararg v: String) = Field(p, Kind.CHOICE, choices = v.toList())

    fun protocol(bean: AbstractBean): String? = when (bean) {
        is io.nekohasekai.sagernet.fmt.sudoku.SudokuBean -> "sudoku"
        is io.nekohasekai.sagernet.fmt.nakis.NakisBean -> "nakis"
        is AnyTLSBean -> "anytls"
        is ShadowTLSBean -> "shadowtls"
        is HttpBean -> "http"
        is TrojanBean -> "trojan"
        is VMessBean -> if (bean.isVLESS) "vless" else "vmess"
        is HysteriaBean -> if (bean.protocolVersion == 2) "hysteria2" else if (bean.protocol == HysteriaBean.PROTOCOL_UDP) "hysteria" else null
        is TuicBean -> if (bean.protocolVersion == 5) "tuic" else null
        is SOCKSBean -> "socks"
        is ShadowsocksBean -> "shadowsocks" // Its supported SIP003 plugins are handled by the native SS builder.
        is SSHBean -> "ssh"
        is WireGuardBean -> "wireguard"
        else -> null // External plugins and raw ConfigBean retain their own editors.
    }

    fun isNativeQuic(bean: AbstractBean) = protocol(bean) in setOf("hysteria", "hysteria2", "tuic")
    fun supportsCustomAlpn(bean: AbstractBean) = protocol(bean) != "hysteria2"
    fun supportsSharedTransport(bean: AbstractBean?) = bean !is HttpBean

    /** Keep the legacy schema for reading old data; only expose controls implemented by this build. */
    fun editableField(bean: AbstractBean, path: String): Boolean = when {
        path == "tls.ech.query_server_name" && isNativeQuic(bean) -> false
        path == "transport.permit_without_stream" -> false // Build uses gRPC Lite, not with_grpc.
        path == "disable_path_mtu_discovery" && protocol(bean) == "hysteria" -> false
        else -> true
    }

    fun fields(bean: AbstractBean): List<Field> {
        val p = protocol(bean) ?: return emptyList()
        if (p == "sudoku" || p == "nakis") return emptyList()
        val result = mutableListOf(duration("connect_timeout"))
        when (p) {
            "anytls" -> result += listOf(duration("idle_session_check_interval"), duration("idle_session_timeout"),
                integer("min_idle_session"), Field("client_metadata", Kind.TEXT))
            "vmess" -> result += listOf(bool("global_padding"), bool("authenticated_length"))
            "hysteria2" -> result += listOf(duration("hop_interval_max"),
                choice("bbr_profile", "standard", "conservative", "aggressive"), bool("disable_chrome_parrot"),
                choice("obfs.type", "salamander", "gecko"), integer("obfs.min_packet_size", 0, 2048), integer("obfs.max_packet_size", 0, 2048))
            "tuic" -> result += listOf(bool("udp_over_stream"), duration("heartbeat"))
            "ssh" -> result += listOf(Field("host_key_algorithms", Kind.LIST), Field("client_version", Kind.TEXT),
                Field("cipher", Kind.LIST), Field("mac", Kind.LIST), Field("kex_algorithm", Kind.LIST))
            "wireguard" -> result += listOf(integer("workers", 1), duration("udp_timeout"))
        }
        if (p in listOf("hysteria", "hysteria2", "tuic")) {
            result += listOf(duration("idle_timeout"), duration("keep_alive_period"),
                integer("max_concurrent_streams", 1), integer("initial_packet_size", 1200, 65527), bool("disable_path_mtu_discovery"))
            if (p != "hysteria") result += listOf(integer("stream_receive_window", 1, Long.MAX_VALUE),
                integer("connection_receive_window", 1, Long.MAX_VALUE))
        }
        if (p in listOf("vmess", "vless", "trojan", "shadowsocks")) {
            // Existing multiplex controls keep responsibility for enabled/protocol/padding.
            if (p == "shadowsocks") result += listOf(bool("multiplex.enabled"), choice("multiplex.protocol", "h2mux", "smux", "yamux"), bool("multiplex.padding"))
            result += listOf(integer("multiplex.max_connections", 1), integer("multiplex.min_streams", 1))
            if (p == "shadowsocks") result += integer("multiplex.max_streams", 1) // VMess/VLESS/Trojan already expose muxConcurrency.
        }
        if (bean is StandardV2RayBean && p in listOf("vmess", "vless", "trojan")) {
            when (bean.type) {
                "http" -> result += listOf(duration("transport.idle_timeout"), duration("transport.ping_timeout"), Field("transport.method", Kind.TEXT))
                "grpc" -> result += listOf(duration("transport.idle_timeout"), duration("transport.ping_timeout"), bool("transport.permit_without_stream"))
            }
        }
        if (p in listOf("anytls", "shadowtls", "http", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic")) {
            result += listOf(duration("tls.handshake_timeout"), Field("tls.certificate_public_key_sha256", Kind.PINS),
                Field("tls.ech.query_server_name", Kind.TEXT))
            if (p !in listOf("hysteria", "hysteria2", "tuic")) {
                result += listOf(choice("tls.min_version", "1.0", "1.1", "1.2", "1.3"),
                    choice("tls.max_version", "1.0", "1.1", "1.2", "1.3"), bool("tls.disable_sni"),
                    Field("tls.curve_preferences", Kind.LIST, choices = listOf("P256", "P384", "P521", "X25519", "X25519MLKEM768")))
            }
            // StandardV2Ray/AnyTLS already have native ECH editors. QUIC gets the same inline options.
            if (p in listOf("hysteria", "hysteria2", "tuic")) {
                result += listOf(bool("tls.ech.enabled"), Field("tls.ech.config", Kind.LIST))
            }
        }
        return result
    }
}
