package moe.matsuri.nb4a.net

import java.io.IOException

internal data class TestNetworkCandidate<T>(
    val network: T,
    val usable: Boolean,
    val vpn: Boolean,
    val ownedByApp: Boolean = false,
)

internal data class TestNetworkSelection<T>(val network: T, val physicalOnly: Boolean)

/** Respect the app's default route unless doing so could enter our own TUN. */
internal object TestNetworkPolicy {
    fun <T> select(
        active: TestNetworkCandidate<T>?,
        candidates: List<TestNetworkCandidate<T>>,
        ownVpnExpected: Boolean,
    ): TestNetworkSelection<T> {
        val ownVpn = ownVpnExpected || candidates.any { it.vpn && it.ownedByApp }
        if (ownVpn) {
            if (active == null && candidates.none { it.vpn && it.ownedByApp }) {
                throw IOException("Test network: default route is blocked or VPN ownership is unavailable")
            }
            if (active?.vpn == true && !active.ownedByApp &&
                candidates.none { it.vpn && it.ownedByApp }
            ) {
                throw IOException("Test network: VPN ownership changed; wait for the service state to settle")
            }
            val physical = (listOfNotNull(active) + candidates).firstOrNull { it.usable && !it.vpn }
                ?: throw IOException("Test network: no usable physical network for the running SakuraBox VPN")
            return TestNetworkSelection(physical.network, physicalOnly = true)
        }
        val selected = active?.takeIf { it.usable && !it.ownedByApp }
            ?: throw IOException("Test network: the application's default network is unavailable or blocked")
        return TestNetworkSelection(selected.network, physicalOnly = false)
    }

    fun <T> checkPinned(expected: TestNetworkSelection<T>, current: TestNetworkSelection<T>) {
        if (expected != current) throw IOException("Test network: application route changed; start a new test")
    }
}
