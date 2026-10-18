package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.shortcut.ShortcutEffects
import io.nekohasekai.sagernet.shortcut.ShortcutExecution
import io.nekohasekai.sagernet.shortcut.ShortcutIntents
import io.nekohasekai.sagernet.shortcut.ShortcutOperation

/** Must remain non-exported. Android's ShortcutManager broker launches registered
 * shortcuts as the publisher; ordinary external startActivity calls cannot enter. */
class TrustedShortcutActivity : Activity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
    private var execution: ShortcutExecution? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        val request = ShortcutIntents.read(intent, ShortcutOperation.fromAction(intent.action))
            ?: run { finish(); return }
        if (request.profileId != null && SagerDatabase.proxyDao.getById(request.profileId) == null) {
            finish(); return
        }
        execution = ShortcutExecution(request)
        connection.connect(this, this)
        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService(ShortcutManager::class.java)?.reportShortcutUsed(
                request.profileId?.let { "shortcut-profile-$it" } ?: request.operation.name.lowercase())
        }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        if (isFinishing || isDestroyed) return
        try {
            val state = BaseService.State.entries.getOrNull(service.state) ?: return
            execution?.execute(state.canStop, state == BaseService.State.Stopped, object : ShortcutEffects {
                override val selectedProfile get() = DataStore.selectedProxy
                override fun profileExists(id: Long) = SagerDatabase.proxyDao.getById(id) != null
                override fun selectProfile(id: Long) { DataStore.selectedProxy = id }
                override fun start() { SagerNet.startService() }
                override fun stop() = SagerNet.stopService()
                override fun reload() = SagerNet.reloadService()
            })
        } finally { finish() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        execution = null
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}
    override fun onBinderDied() { execution = null; finish() }
    override fun onServiceDisconnected() { execution = null; finish() }

    override fun onDestroy() {
        execution = null
        connection.disconnect(this)
        super.onDestroy()
    }
}
