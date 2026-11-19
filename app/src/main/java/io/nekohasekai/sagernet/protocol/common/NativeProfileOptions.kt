package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.nakis.NakisBean
import io.nekohasekai.sagernet.fmt.nakis.NakisOptions
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuBean
import io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean

/** Converts a saved bean to the native option before the chain assigns routing and advanced fields. */
internal fun buildNativeProfileOptions(bean: AbstractBean, tag: String): SingBoxOption = when (bean) {
    is ConfigBean -> CustomSingBoxOption(bean.config)

    is ShadowTLSBean -> // before StandardV2RayBean
        buildSingBoxOutboundShadowTLSBean(bean)

    is StandardV2RayBean -> // http/trojan/vmess/vless
        buildSingBoxOutboundStandardV2RayBean(bean)

    is HysteriaBean ->
        buildSingBoxOutboundHysteriaBean(bean)

    is TuicBean ->
        buildSingBoxOutboundTuicBean(bean)

    is SOCKSBean ->
        buildSingBoxOutboundSocksBean(bean)

    is ShadowsocksBean ->
        buildSingBoxOutboundShadowsocksBean(bean)

    is WireGuardBean ->
        buildSingBoxEndpointWireguardBean(bean).apply { this.tag = tag }

    is SSHBean ->
        buildSingBoxOutboundSSHBean(bean)

    is SudokuBean ->
        SudokuOptions.buildOutbound(bean)

    is NakisBean ->
        NakisOptions.buildOutbound(bean)

    is AnyTLSBean ->
        buildSingBoxOutboundAnyTLSBean(bean)

    else -> throw IllegalStateException("can't reach")
}

