package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.ImportLimits

internal suspend fun normalizeSubscriptionNames(proxies: List<AbstractBean>): List<AbstractBean> {
    currentCoroutineContext().ensureActive()
    ImportLimits.checkNodeCount(proxies.size.toLong())
    val proxiesMap = LinkedHashMap<String, AbstractBean>()
    val names = ImportLimits.Names()
    for (proxy in proxies) {
        currentCoroutineContext().ensureActive()
        val original = proxy.displayName()
        val name = names.allocate(original)
        if (name != original) proxy.name = name
        proxiesMap[name] = proxy
    }
    return proxiesMap.values.toList()
}

internal suspend fun deduplicateSubscriptionProfiles(
    proxies: List<AbstractBean>,
): Pair<List<AbstractBean>, List<String>> {
    val duplicate = ArrayList<String>()
    Logs.d("Before deduplication: ${proxies.size}")
    val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
    val uniqueNames = HashMap<Protocols.Deduplication, String>()
    val uniqueIndexes = HashMap<Protocols.Deduplication, Int>()
    for (_proxy in proxies) {
        currentCoroutineContext().ensureActive()
        val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
        if (!uniqueProxies.add(proxy)) {
            val index = uniqueIndexes.getValue(proxy)
            if (uniqueNames.containsKey(proxy)) {
                val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                if (name.isNotBlank()) {
                    duplicate.add("$name ($index)")
                    uniqueNames[proxy] = ""
                }
            }
            duplicate.add(_proxy.displayName() + " ($index)")
        } else {
            uniqueIndexes[proxy] = uniqueProxies.size - 1
            uniqueNames[proxy] = _proxy.displayName()
        }
    }
    uniqueProxies.retainAll(uniqueNames.keys)
    return uniqueProxies.toList().map { it.bean } to duplicate
}
