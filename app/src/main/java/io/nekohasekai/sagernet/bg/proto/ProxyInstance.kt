package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    @Volatile var looper: TrafficLooper? = null
        private set
    private var closed = false

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d("Configuration built: ${config.trafficMap.size} traffic groups")
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        service?.let {
            val statistics = TrafficLooper(it.data, this)
            looper = statistics
            statistics.prepare()
        }
        Logs.d("Plugin configurations prepared: ${pluginConfigs.size}")
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        super.launch() // start box
        looper?.start()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runBlocking {
            looper?.pause()
            try {
                super.close()
            } finally {
                looper?.finish()
                looper = null
            }
        }
    }
}
