package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.utils.ImportLimits
import moe.matsuri.nb4a.utils.SafeYaml

@Suppress("UNCHECKED_CAST")
internal suspend fun parseClashSubscription(text: String): List<AbstractBean> {
    val proxies = mutableListOf<AbstractBean>()
    val yaml = runInterruptible(Dispatchers.Default) { SafeYaml.loadMap(text) }

    val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

    val yamlProxies = yaml["proxies"] as? List<Map<String, Any?>>
        ?: error(app.getString(R.string.no_proxies_found_in_file))
    ImportLimits.checkNodeCount(yamlProxies.size.toLong())
    for (proxy in yamlProxies) {
        currentCoroutineContext().ensureActive()
        // Note: YAML numbers parsed as "Long"

        when (proxy["type"] as String) {
            "socks5" -> {
                proxies.add(SOCKSBean().apply {
                    serverAddress = proxy["server"] as String
                    serverPort = proxy["port"].toString().toInt()
                    username = proxy["username"]?.toString()
                    password = proxy["password"]?.toString()
                    name = proxy["name"]?.toString()
                })
            }

            "http" -> {
                proxies.add(HttpBean().apply {
                    serverAddress = proxy["server"] as String
                    serverPort = proxy["port"].toString().toInt()
                    username = proxy["username"]?.toString()
                    password = proxy["password"]?.toString()
                    setTLS(proxy["tls"]?.toString() == "true")
                    sni = proxy["sni"]?.toString()
                    name = proxy["name"]?.toString()
                    allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
                })
            }

            "ss" -> {
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
                proxies.add(ShadowsocksBean().apply {
                    serverAddress = proxy["server"] as String
                    serverPort = proxy["port"].toString().toInt()
                    password = proxy["password"]?.toString()
                    method = clashCipher(proxy["cipher"] as String)
                    plugin = ssPlugin.joinToString(";")
                    name = proxy["name"]?.toString()
                })
            }

            "vmess", "vless", "trojan" -> {
                val bean = when (proxy["type"] as String) {
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

                bean.serverAddress = proxy["server"]?.toString() ?: continue
                bean.serverPort = proxy["port"]?.toString()?.toIntOrNull() ?: continue

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

                        "tls" -> if (bean is VMessBean) {
                            bean.security =
                                if (opt.value as? Boolean == true) "tls" else ""
                        }

                        "servername", "sni" -> bean.sni = opt.value?.toString()

                        "alpn" -> bean.alpn =
                            (opt.value as? List<Any>)?.joinToString("\n")

                        "skip-cert-verify" -> bean.allowInsecure =
                            opt.value as? Boolean == true

                        "client-fingerprint" -> bean.utlsFingerprint =
                            opt.value as String

                        "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                            for (realityOpt in it) {
                                bean.security = "tls"

                                when (realityOpt.key) {
                                    "public-key" -> bean.realityPubKey =
                                        realityOpt.value?.toString()

                                    "short-id" -> bean.realityShortId =
                                        realityOpt.value?.toString()
                                }
                            }
                        }

                        "network" -> {
                            when (opt.value) {
                                "h2", "http" -> bean.type = "http"
                                "ws", "grpc" -> bean.type = opt.value as String
                            }
                        }

                        "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                            for (wsOpt in it) {
                                when (wsOpt.key) {
                                    "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                        when (key.toString().lowercase()) {
                                            "host" -> {
                                                bean.host = value?.toString()
                                            }
                                        }
                                    }

                                    "path" -> {
                                        bean.path = wsOpt.value?.toString()
                                    }

                                    "max-early-data" -> {
                                        bean.wsMaxEarlyData =
                                            wsOpt.value?.toString()?.toIntOrNull()
                                    }

                                    "early-data-header-name" -> {
                                        bean.earlyDataHeaderName =
                                            wsOpt.value?.toString()
                                    }

                                    "v2ray-http-upgrade" -> {
                                        if (wsOpt.value as? Boolean == true) {
                                            bean.type = "httpupgrade"
                                        }
                                    }
                                }
                            }
                        }

                        "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                            for (h2Opt in it) {
                                when (h2Opt.key) {
                                    "host" -> bean.host =
                                        (h2Opt.value as? List<Any>)?.joinToString("\n")

                                    "path" -> bean.path = h2Opt.value?.toString()
                                }
                            }
                        }

                        "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                            for (httpOpt in it) {
                                when (httpOpt.key) {
                                    "path" -> bean.path =
                                        (httpOpt.value as? List<Any>)?.joinToString("\n")

                                    "headers" -> {
                                        (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                            when (key.toString().lowercase()) {
                                                "host" -> {
                                                    bean.host = value.joinToString("\n")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                            for (grpcOpt in it) {
                                when (grpcOpt.key) {
                                    "grpc-service-name" -> bean.path =
                                        grpcOpt.value?.toString()
                                }
                            }
                        }

                        "smux" -> (opt.value as? Map<String, Any?>)?.also {
                            for (smuxOpt in it) {
                                when (smuxOpt.key) {
                                    "enabled" -> bean.enableMux =
                                        smuxOpt.value.toString() == "true"

                                    "max-streams" -> bean.muxConcurrency =
                                        smuxOpt.value.toString().toInt()

                                    "padding" -> bean.muxPadding =
                                        smuxOpt.value.toString() == "true"
                                }
                            }
                        }

                        "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                            for (echOpt in it) {
                                when (echOpt.key) {
                                    "enable" -> bean.enableECH =
                                        echOpt.value.toString() == "true"
                                }
                            }
                        }
                    }
                }
                proxies.add(bean)
            }

            "anytls" -> {
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
                            val field = io.nekohasekai.sagernet.fmt.ProtocolOptions.fields(bean).first { it.path == path }
                            bean.protocolOptionsJson = io.nekohasekai.sagernet.fmt.ProtocolOptions.set(
                                io.nekohasekai.sagernet.fmt.ProtocolOptions.parse(bean.protocolOptionsJson), field, opt.value.toString()
                            ).toString()
                        }
                    }
                }
                proxies.add(bean)
            }

            "hysteria" -> {
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
                proxies.add(bean)
            }

            "hysteria2" -> {
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
                proxies.add(bean)
            }

            "tuic" -> {
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
                proxies.add(bean)
            }
        }
    }

    // Fix ent
    proxies.forEach {
        it.initializeDefaultValues()
        if (it is StandardV2RayBean) {
            // 1. SNI
            if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                it.sni = it.host
            }
            // 2. globalClientFingerprint
            if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                it.utlsFingerprint = globalClientFingerprint
                if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
            }
        }
    }
    return proxies
}

private fun clashCipher(cipher: String): String {
    return when (cipher) {
        "dummy" -> "none"
        else -> cipher
    }
}
