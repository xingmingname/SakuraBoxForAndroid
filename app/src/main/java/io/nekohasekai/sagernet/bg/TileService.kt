package io.nekohasekai.sagernet.bg

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.branding.BrandTheme
import io.nekohasekai.sagernet.branding.BrandThemeManager
import io.nekohasekai.sagernet.database.SagerDatabase
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    private val iconIdle by lazy { Icon.createWithResource(this, R.drawable.ic_service_idle) }
    private val iconBusy by lazy { Icon.createWithResource(this, R.drawable.ic_service_busy) }
    private val iconConnected by lazy {
        Icon.createWithResource(this, R.drawable.ic_service_active)
    }
    private val iconNeko by lazy { Icon.createWithResource(this, R.drawable.ic_neko_tile) }
    private var tapPending = false
    private var serviceState = BaseService.State.Idle
    private var profileName: String? = null
    private var brandUpdates: AutoCloseable? = null

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
        if (tapPending) {
            tapPending = false
            onClick()
        }
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = SagerDatabase.proxyDao.getById(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onTileAdded() {
        super.onTileAdded()
        renderTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        stopBrandUpdates()
        brandUpdates = BrandThemeManager.observe(this, ::renderTile)
        renderTile()
        connection.connect(this, this)
    }

    private fun stopBrandUpdates() {
        brandUpdates?.close()
        brandUpdates = null
    }

    override fun onStopListening() {
        stopBrandUpdates()
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onDestroy() {
        stopBrandUpdates()
        super.onDestroy()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::toggle) else toggle()
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        this.serviceState = serviceState
        this.profileName = profileName
        renderTile()
    }

    private fun renderTile() {
        qsTile?.apply {
            val brand = BrandThemeManager.appearance(this@TileService)
            val connectedProfile = profileName.takeIf { serviceState.connected }
            val brandLabel = brand.tileTitle(this@TileService)
            label = when {
                brand.theme == BrandTheme.SAKURA && connectedProfile != null -> connectedProfile
                brand.theme == BrandTheme.CUSTOM && Build.VERSION.SDK_INT < 29 && connectedProfile != null ->
                    getString(R.string.brand_tile_with_node, brandLabel, connectedProfile)
                else -> brandLabel
            }
            if (Build.VERSION.SDK_INT >= 29) {
                subtitle = connectedProfile.takeUnless { brand.theme == BrandTheme.SAKURA }
            }
            icon = brand.custom?.tileIcon?.let(Icon::createWithBitmap) ?: if (brand.theme == BrandTheme.NEKO) iconNeko else when (serviceState) {
                BaseService.State.Connected -> iconConnected
                BaseService.State.Connecting, BaseService.State.Stopping -> iconBusy
                BaseService.State.Stopped, BaseService.State.Idle -> iconIdle
            }
            when (serviceState) {
                BaseService.State.Connecting, BaseService.State.Connected -> state = Tile.STATE_ACTIVE
                BaseService.State.Stopping -> state = Tile.STATE_UNAVAILABLE
                BaseService.State.Stopped -> state = Tile.STATE_INACTIVE
                BaseService.State.Idle -> Unit // Wait for the existing service binding to report state.
            }
            updateTile()
        }
    }

    private fun toggle() {
        val service = connection.service
        if (service == null) tapPending =
            true else BaseService.State.values()[service.state].let { state ->
            when {
                state.canStop -> SagerNet.stopService()
                state == BaseService.State.Stopped -> SagerNet.startService()
            }
        }
    }
}
