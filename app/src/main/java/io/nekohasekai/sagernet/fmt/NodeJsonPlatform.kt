package io.nekohasekai.sagernet.fmt

import android.net.NetworkCapabilities
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.SagerNet

internal fun requireAndroidJsonVpn(root: JsonObject, vpn: Boolean, legacy: Boolean = false) {
    val physical = if (vpn) try {
        SagerNet.connectivity.allNetworks.mapNotNull { network ->
            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true)
                SagerNet.connectivity.getLinkProperties(network)?.interfaceName else null
        }.toSet()
    } catch (_: Exception) { emptySet() } else emptySet()
    if (legacy) NodeJson.requireLegacyVpnProtection(root, vpn, physical)
    else NodeJson.requireVpn(root, vpn, physical)
}
