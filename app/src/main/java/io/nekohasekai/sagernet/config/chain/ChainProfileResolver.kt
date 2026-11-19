package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.ChainBean

/** Expands saved profiles before ConfigBuilder assigns outbound tags and routes. */
internal class ChainProfileResolver {
    private val chainGraph = ChainGraph(
        { SagerNet.application.getString(R.string.circular_reference_sum) },
        { SagerNet.application.getString(R.string.chain_nesting_too_deep) })

    private fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        require(bean.jsonMode != NodeJson.FULL) { "A complete JSON profile cannot be embedded in a chain or routing rule" }
        if (bean.jsonMode == NodeJson.OUTBOUND) return mutableListOf(this)
        if (bean is ChainBean) return chainGraph.within(id) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun resolve(profile: ProxyEntity): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(profile.groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = profile.resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }
}
