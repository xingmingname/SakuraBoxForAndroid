@file:Suppress("UNCHECKED_CAST")

package io.nekohasekai.sagernet.subscriptions.clash

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.ProtocolOptions
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.ktx.isIpAddress
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean

internal fun mapClashProxy(proxy: Map<String, Any?>): AbstractBean? =
    when (val type = proxy["type"] as String) {
        "socks5" -> SOCKSBean().apply {
            serverAddress = proxy["server"] as String
            serverPort = proxy["port"].toString().toInt()
            username = proxy["username"]?.toString()
            password = proxy["password"]?.toString()
            name = proxy["name"]?.toString()
        }

        "http" -> HttpBean().apply {
            serverAddress = proxy["server"] as String
            serverPort = proxy["port"].toString().toInt()
            username = proxy["username"]?.toString()
            password = proxy["password"]?.toString()
            setTLS(proxy["tls"]?.toString() == "true")
            sni = proxy["sni"]?.toString()
            name = proxy["name"]?.toString()
            allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
        }

        "ss" -> mapShadowsocks(proxy)
        "vmess", "vless", "trojan" -> mapV2Ray(proxy, type)
        "anytls" -> mapAnyTLS(proxy)
        "hysteria" -> mapHysteria(proxy)
        "hysteria2" -> mapHysteria2(proxy)
        "tuic" -> mapTuic(proxy)
        else -> null
    }

private fun mapShadowsocks(proxy: Map<String, Any?>): ShadowsocksBean {
    val ssPlugin = mutableListOf<String>()
    if (proxy.contains("plugin")) {
        val opts = proxy["plugin-opts"] as Map<String, Any?>
        when (proxy["plugin"]) {
            "obfs" -> {
                ssPlugin.apply {
                    add("obfs-local")
                    add("obfs=" + (opts["mode"]?.toString() ?: ""))
                    add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                }
            }

            "v2ray-plugin" -> {
                ssPlugin.apply {
                    add("v2ray-plugin")
                    add("mode=" + (opts["mode"]?.toString() ?: ""))
                    if (opts["mode"]?.toString() == "true") add("tls")
                    add("host=" + (opts["host"]?.toString() ?: ""))
                    add("path=" + (opts["path"]?.toString() ?: ""))
                    if (opts["mux"]?.toString() == "true") add("mux=8")
                }
            }
        }
    }
    return ShadowsocksBean().apply {
        serverAddress = proxy["server"] as String
        serverPort = proxy["port"].toString().toInt()
        password = proxy["password"]?.toString()
        method = clashCipher(proxy["cipher"] as String)
        plugin = ssPlugin.joinToString(";")
        name = proxy["name"]?.toString()
    }
}

private fun mapV2Ray(proxy: Map<String, Any?>, type: String): StandardV2RayBean? {
    val bean = when (type) {
        "vmess" -> VMessBean()
        "vless" -> VMessBean().apply {
            alterId = -1 // make it VLESS
            packetEncoding = 2 // clash meta default XUDP
        }

        "trojan" -> TrojanBean().apply {
            security = "tls"
        }

        else -> error("impossible")
    }

    bean.serverAddress = proxy["server"]?.toString() ?: return null
    bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: return null

    // Keep YAML field order for combinations such as network and WebSocket upgrade.
    for (opt in proxy) {
        when (opt.key) {
            "name" -> bean.name = opt.value?.toString()
            "password" -> if (bean is TrojanBean) bean.password =
                opt.value?.toString()

            "uuid" -> if (bean is VMessBean) bean.uuid =
                opt.value?.toString()

            "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                opt.value?.toString()?.toIntOrNull()

            "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                (opt.value as? String)

            "flow" -> if (bean is VMessBean && bean.isVLESS) {
                (opt.value as? String)?.let {
                    if (it.contains("xtls-rprx-vision")) {
                        bean.encryption = "xtls-rprx-vision"
                    }
                }
            }

            "packet-encoding" -> if (bean is VMessBean) {
                bean.packetEncoding = when ((opt.value as? String)) {
                    "packetaddr" -> 1
                    "xudp" -> 2
                    else -> 0
                }
            }
            else -> applyClashV2RayOption(bean, opt.key, opt.value)
        }
    }
    return bean
}

private fun mapAnyTLS(proxy: Map<String, Any?>): AnyTLSBean {
    val bean = AnyTLSBean()
    for (opt in proxy) {
        if (opt.value == null) continue
        when (opt.key.replace("_", "-")) {
            "name" -> bean.name = opt.value.toString()
            "server" -> bean.serverAddress = opt.value as String
            "port" -> bean.serverPort = opt.value.toString().toInt()
            "password" -> bean.password = opt.value.toString()
            "client-fingerprint" -> bean.utlsFingerprint =
                opt.value as String

            "sni" -> bean.sni = opt.value.toString()
            "skip-cert-verify" -> bean.allowInsecure =
                opt.value.toString() == "true"

            "alpn" -> {
                val alpn = (opt.value as? (List<String>))
                bean.alpn = alpn?.joinToString("\n")
            }
            "idle-session-check-interval", "idle-session-timeout", "min-idle-session" -> {
                val path = opt.key.replace('-', '_')
                val field = ProtocolOptions.fields(bean).first { it.path == path }
                bean.protocolOptionsJson = ProtocolOptions.set(
                    ProtocolOptions.parse(bean.protocolOptionsJson),
                    field,
                    opt.value.toString()
                ).toString()
            }
        }
    }
    return bean
}

private fun mapHysteria(proxy: Map<String, Any?>): HysteriaBean {
    val bean = HysteriaBean()
    bean.protocolVersion = 1
    var hopPorts = ""
    for (opt in proxy) {
        if (opt.value == null) continue
        when (opt.key.replace("_", "-")) {
            "name" -> bean.name = opt.value.toString()
            "server" -> bean.serverAddress = opt.value as String
            "port" -> bean.serverPorts = opt.value.toString()
            "ports" -> hopPorts = opt.value.toString()

            "obfs" -> bean.obfuscation = opt.value.toString()

            "auth-str" -> {
                bean.authPayloadType = HysteriaBean.TYPE_STRING
                bean.authPayload = opt.value.toString()
            }

            "sni" -> bean.sni = opt.value.toString()

            "skip-cert-verify" -> bean.allowInsecure =
                opt.value.toString() == "true"

            "up" -> bean.uploadMbps =
                opt.value.toString().substringBefore(" ").toIntOrNull()
                    ?: 100

            "down" -> bean.downloadMbps =
                opt.value.toString().substringBefore(" ").toIntOrNull()
                    ?: 100

            "recv-window-conn" -> bean.connectionReceiveWindow =
                opt.value.toString().toIntOrNull() ?: 0

            "recv-window" -> bean.streamReceiveWindow =
                opt.value.toString().toIntOrNull() ?: 0

            "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                opt.value.toString() == "true" || opt.value.toString() == "1"

            "alpn" -> {
                val alpn = (opt.value as? (List<String>))
                bean.alpn = alpn?.joinToString("\n") ?: "h3"
            }
        }
    }
    if (hopPorts.isNotBlank()) {
        bean.serverPorts = hopPorts
    }
    return bean
}

private fun mapHysteria2(proxy: Map<String, Any?>): HysteriaBean {
    val bean = HysteriaBean()
    bean.protocolVersion = 2
    var hopPorts = ""
    for (opt in proxy) {
        if (opt.value == null) continue
        when (opt.key.replace("_", "-")) {
            "name" -> bean.name = opt.value.toString()
            "server" -> bean.serverAddress = opt.value as String
            "port" -> bean.serverPorts = opt.value.toString()
            "ports" -> hopPorts = opt.value.toString()

            "obfs-password" -> bean.obfuscation = opt.value.toString()

            "password" -> bean.authPayload = opt.value.toString()

            "sni" -> bean.sni = opt.value.toString()

            "skip-cert-verify" -> bean.allowInsecure =
                opt.value.toString() == "true"

            "up" -> bean.uploadMbps =
                opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

            "down" -> bean.downloadMbps =
                opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
        }
    }
    if (hopPorts.isNotBlank()) {
        bean.serverPorts = hopPorts
    }
    return bean
}

private fun mapTuic(proxy: Map<String, Any?>): TuicBean {
    val bean = TuicBean()
    var ip = ""
    for (opt in proxy) {
        if (opt.value == null) continue
        when (opt.key.replace("_", "-")) {
            "name" -> bean.name = opt.value.toString()
            "server" -> bean.serverAddress = opt.value.toString()
            "ip" -> ip = opt.value.toString()
            "port" -> bean.serverPort = opt.value.toString().toInt()

            "token" -> {
                bean.protocolVersion = 4
                bean.token = opt.value.toString()
            }

            "uuid" -> bean.uuid = opt.value.toString()

            "password" -> bean.token = opt.value.toString()

            "skip-cert-verify" -> bean.allowInsecure =
                opt.value.toString() == "true"

            "disable-sni" -> bean.disableSNI =
                opt.value.toString() == "true"

            "reduce-rtt" -> bean.reduceRTT =
                opt.value.toString() == "true"

            "sni" -> bean.sni = opt.value.toString()

            "alpn" -> {
                val alpn = (opt.value as? (List<String>))
                bean.alpn = alpn?.joinToString("\n")
            }

            "congestion-controller" -> bean.congestionController =
                opt.value.toString()

            "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

        }
    }
    if (ip.isNotBlank()) {
        bean.serverAddress = ip
        if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
            bean.sni = bean.serverAddress
        }
    }
    return bean
}

private fun clashCipher(cipher: String): String {
    return when (cipher) {
        "dummy" -> "none"
        else -> cipher
    }
}
