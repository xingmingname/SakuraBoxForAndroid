package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.utils.ImportLimits
import org.ini4j.Ini
import java.io.StringReader

internal fun parseWireGuardSubscription(conf: String): List<WireGuardBean>? {
    ImportLimits.checkText(conf)
    val ini = Ini(StringReader(conf))
    val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
    val bean = WireGuardBean().applyDefaultValues()
    val localAddresses = iface.getAll("Address")
    if (localAddresses.isNullOrEmpty()) error("Empty address in 'Interface' selection")
    bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
    bean.privateKey = iface["PrivateKey"]
    val mtuText = iface["MTU"]?.trim()
    if (!mtuText.isNullOrEmpty()) {
        val mtu = mtuText.toIntOrNull()
        if (mtu == null || mtu < 0) {
            Logs.w("Invalid WireGuard MTU in [Interface]")
            return null
        }
        bean.mtu = mtu
    }
    val peers = ini.getAll("Peer")
    if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")
    val beans = mutableListOf<WireGuardBean>()
    ImportLimits.checkNodeCount(peers.size.toLong())
    for (peer in peers) {
        ImportLimits.checkCancelled()
        val endpoint = peer["Endpoint"]
        if (endpoint.isNullOrBlank() || !endpoint.contains(":")) {
            continue
        }

        val peerBean = bean.clone()
        peerBean.serverAddress = endpoint.substringBeforeLast(":")
        peerBean.serverPort = endpoint.substringAfterLast(":").toIntOrNull() ?: continue
        peerBean.peerPublicKey = peer["PublicKey"] ?: continue
        peerBean.peerPreSharedKey = peer["PresharedKey"]
        beans.add(peerBean.applyDefaultValues())
    }
    if (beans.isEmpty()) error("Empty available peer list")
    return beans
}
