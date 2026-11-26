package io.nekohasekai.sagernet.subscriptions.clash

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import moe.matsuri.nb4a.utils.ImportLimits
import moe.matsuri.nb4a.utils.SafeYaml
import org.yaml.snakeyaml.error.YAMLException

@Suppress("UNCHECKED_CAST")
internal suspend fun parseClashSubscription(text: String): List<AbstractBean>? {
    val yaml = try {
        runInterruptible(Dispatchers.Default) { SafeYaml.loadMap(text) }
    } catch (e: YAMLException) {
        Logs.w(e)
        return null
    }

    val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

    val yamlProxies = yaml["proxies"] as? List<Map<String, Any?>>
        ?: error(app.getString(R.string.no_proxies_found_in_file))
    ImportLimits.checkNodeCount(yamlProxies.size.toLong())
    val proxies = mutableListOf<AbstractBean>()
    for (proxy in yamlProxies) {
        currentCoroutineContext().ensureActive()
        mapClashProxy(proxy)?.let(proxies::add)
    }

    // Fill Bean defaults before deriving Clash TLS defaults from the imported fields.
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
