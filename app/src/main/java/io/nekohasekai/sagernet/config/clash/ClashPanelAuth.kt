package io.nekohasekai.sagernet.ui

import com.google.gson.Gson
import moe.matsuri.nb4a.utils.ClashApiSecretStore
import java.net.URI
import java.net.URLDecoder

/** This origin is served exclusively from private UI files by WebviewFragment.
 * No credentials in URLs, a JavaScript bridge, or pages fetched from loopback. */
internal const val CLASH_PANEL_CSP = "worker-src 'none'; child-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"

internal fun clashPanelBootstrap(url: String, credentials: ClashApiSecretStore.Credentials): String {
    require(ClashApiSecretStore.isPanelOrigin(credentials.panelOrigin)) { "Invalid private panel origin" }
    val requested = URI(url)
    val backend = URI("http://${credentials.controller}")
    require(requested.scheme == "http" && requested.host in listOf("127.0.0.1", "localhost", "[::1]", "::1") &&
        requested.host == backend.host && requested.port == backend.port &&
        requested.rawUserInfo == null && requested.rawFragment == null &&
        (requested.path == "/ui" || requested.path.startsWith("/ui/"))) {
        "Automatic API authentication is only available for the active local built-in panel"
    }
    require(requested.rawQuery.orEmpty().split('&').none {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") in listOf("secret", "hostname", "port")
    }) { "Remove API credential or backend overrides from the panel URL" }
    val json = Gson() // HTML-safe JSON escaping, including an explicit custom secret.
    val base = json.toJson("http://${credentials.controller}")
    val secret = json.toJson(credentials.secret)
    val target = json.toJson(credentials.panelOrigin + (if (requested.path == "/ui") "/ui/" else requested.rawPath) +
        (requested.rawQuery?.let { "?$it" } ?: ""))
    return """<!doctype html><meta charset="utf-8"><meta name="referrer" content="no-referrer"><meta http-equiv="Content-Security-Policy" content="$CLASH_PANEL_CSP"><script>
        (()=>{let state={};try{state=JSON.parse(localStorage.getItem('yacd.metacubex.one')||'{}')}catch(e){}
        const baseURL=$base;const secret=$secret;
        const list=Array.isArray(state.clashAPIConfigs)?state.clashAPIConfigs:[];
        state.clashAPIConfigs=[{baseURL,secret,addedAt:Date.now()},...list.filter(x=>x.baseURL!==baseURL)];
        state.selectedClashAPIConfigIndex=0;
        localStorage.setItem('yacd.metacubex.one',JSON.stringify(state));location.replace($target)})();
        </script>""".trimIndent()
}
