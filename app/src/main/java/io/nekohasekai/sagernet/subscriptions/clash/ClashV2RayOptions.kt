package io.nekohasekai.sagernet.subscriptions.clash

import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean

@Suppress("UNCHECKED_CAST")
internal fun applyClashV2RayOption(bean: StandardV2RayBean, key: String, value: Any?) {
    when (key) {
        "tls" -> if (bean is VMessBean) {
            bean.security =
                if (value as? Boolean == true) "tls" else ""
        }

        "servername", "sni" -> bean.sni = value?.toString()

        "alpn" -> bean.alpn =
            (value as? List<Any>)?.joinToString("\n")

        "skip-cert-verify" -> bean.allowInsecure =
            value as? Boolean == true

        "client-fingerprint" -> bean.utlsFingerprint =
            value as String

        "reality-opts" -> (value as? Map<String, Any?>)?.also {
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
            when (value) {
                "h2", "http" -> bean.type = "http"
                "ws", "grpc" -> bean.type = value as String
            }
        }

        "ws-opts" -> (value as? Map<String, Any?>)?.also {
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

        "h2-opts" -> (value as? Map<String, Any?>)?.also {
            for (h2Opt in it) {
                when (h2Opt.key) {
                    "host" -> bean.host =
                        (h2Opt.value as? List<Any>)?.joinToString("\n")

                    "path" -> bean.path = h2Opt.value?.toString()
                }
            }
        }

        "http-opts" -> (value as? Map<String, Any?>)?.also {
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

        "grpc-opts" -> (value as? Map<String, Any?>)?.also {
            for (grpcOpt in it) {
                when (grpcOpt.key) {
                    "grpc-service-name" -> bean.path =
                        grpcOpt.value?.toString()
                }
            }
        }

        "smux" -> (value as? Map<String, Any?>)?.also {
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

        "ech-opts" -> (value as? Map<String, Any?>)?.also {
            for (echOpt in it) {
                when (echOpt.key) {
                    "enable" -> bean.enableECH =
                        echOpt.value.toString() == "true"
                }
            }
        }
    }
}
