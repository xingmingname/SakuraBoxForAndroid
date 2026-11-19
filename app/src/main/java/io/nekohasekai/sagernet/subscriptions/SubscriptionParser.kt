package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.nakis.NakisShareLink
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardSubscription
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.ImportLimits
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.error.YAMLException
import java.io.InterruptedIOException

internal object SubscriptionParser {
    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? {
        currentCoroutineContext().ensureActive()
        ImportLimits.checkText(text)
        if (text.trimStart().startsWith(NakisShareLink.PREFIX, ignoreCase = true)) {
            return parseProxies(text).takeIf { it.isNotEmpty() }
        }

        if (text.contains("proxies:")) {
            try {
                return parseClashSubscription(text)
            } catch (e: YAMLException) {
                Logs.w(e)
            }
        } else if (text.contains("[Interface]")) {
            // wireguard
            try {
                val peers = runInterruptible(Dispatchers.Default) { parseWireGuardSubscription(text) } ?: return null
                val proxies = mutableListOf<AbstractBean>()
                proxies.addAll(peers.map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                })
                return proxies
            } catch (e: Exception) {
                if (e is ImportLimits.InputLimitException || e is InterruptedIOException || e is CancellationException) throw e
                Logs.w(e)
            }
        }

        try {
            val json = runInterruptible(Dispatchers.Default) { JSONTokener(text).nextValue() }
            return runInterruptible(Dispatchers.Default) { parseJSON(json) }
        } catch (ignored: Exception) {
            if (ignored is ImportLimits.InputLimitException || ignored is InterruptedIOException || ignored is CancellationException) throw ignored
        }

        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (e: Exception) {
            if (e is ImportLimits.InputLimitException || e is InterruptedIOException || e is CancellationException) throw e
            Logs.w(e)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (ignored: Exception) {
            if (ignored is ImportLimits.InputLimitException || ignored is InterruptedIOException || ignored is CancellationException) throw ignored
        }

        return null
    }

    private fun parseJSON(json: Any): List<AbstractBean> {
        ImportLimits.checkCancelled()
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            when {
                json.optString("type") == "nakis" -> {
                    io.nekohasekai.sagernet.fmt.nakis.NakisOptions.importOutbound(json.toString())?.let { return listOf(it) }
                }
                json.optString("type") == "sudoku" -> {
                    io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions.importOutbound(json.toString())?.let { return listOf(it) }
                }
                json.optString("type") == "anytls" -> {
                    moe.matsuri.nb4a.proxy.anytls.importAnyTLSOutbound(json.toString())?.let { return listOf(it) }
                    return listOf(ConfigBean().applyDefaultValues().apply { type = 1; config = json.toStringPretty() })
                }
                json.has("type") && json.has("server") && json.has("server_port") -> {
                    // A typed sing-box outbound must not enter the legacy Hysteria/SS JSON heuristics:
                    // they cannot represent modern QUIC, multiplex or other advanced options losslessly.
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1; config = json.toStringPretty(); name = json.getStr("tag")
                    })
                }
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("endpoints") && json.getJSONArray("endpoints").length() > 0 -> {
                    return listOfNotNull(io.nekohasekai.sagernet.fmt.importEndpointConfig(json.toStringPretty()))
                }

                json.has("outbounds") -> {
                    ImportLimits.checkNodeCount(json.getJSONArray("outbounds").length().toLong())
                    return json.getJSONArray("outbounds")
                        .filterIsInstance<JSONObject>()
                        .mapNotNull {
                            ImportLimits.checkCancelled()
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ImportLimits.checkCancelled()
                            (if (it.optString("type") == "sudoku") io.nekohasekai.sagernet.fmt.sudoku.SudokuOptions.importOutbound(it.toString()) else null)
                                ?: (if (it.optString("type") == "nakis") io.nekohasekai.sagernet.fmt.nakis.NakisOptions.importOutbound(it.toString()) else null)
                                ?: moe.matsuri.nb4a.proxy.anytls.importAnyTLSOutbound(it.toString()) ?: ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                name = it.getStr("tag")
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            ImportLimits.checkNodeCount(json.length().toLong())
            json.forEach { _, it ->
                ImportLimits.checkCancelled()
                if (isJsonObjectValid(it)) {
                    val parsed = parseJSON(it)
                    ImportLimits.checkNodeCount(proxies.size.toLong() + parsed.size)
                    proxies.addAll(parsed)
                }
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
