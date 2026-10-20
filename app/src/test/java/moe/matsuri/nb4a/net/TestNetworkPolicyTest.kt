package moe.matsuri.nb4a.net

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TestNetworkPolicyTest {
    private val wifi = TestNetworkCandidate("wifi", true, false)
    private val cell = TestNetworkCandidate("cell", true, false)
    private val external = TestNetworkCandidate("external-vpn", true, true)
    private val own = TestNetworkCandidate("sakurabox-vpn", true, true, true)

    private fun refused(action: () -> Unit) {
        try { action(); fail("Ineligible route was accepted") } catch (_: IOException) { }
    }

    @Test fun noVpnUsesTheCurrentDefaultNotStaleUnderlying() {
        assertEquals(TestNetworkSelection("cell", false),
            TestNetworkPolicy.select(cell, listOf(wifi, cell), false))
    }

    @Test fun externalVpnIncludedUsesVpnEvenWithReachablePhysicalNetworks() {
        assertEquals(TestNetworkSelection("external-vpn", false),
            TestNetworkPolicy.select(external, listOf(wifi, cell, external), false))
    }

    @Test fun externalVpnExcludedUsesUidDefaultPhysicalNetwork() {
        assertEquals(TestNetworkSelection("wifi", false),
            TestNetworkPolicy.select(wifi, listOf(external, wifi), false))
    }

    @Test fun ownVpnUsesPhysicalNetworkWithoutEnteringItsTun() {
        assertEquals(TestNetworkSelection("wifi", true),
            TestNetworkPolicy.select(own, listOf(own, wifi), true))
    }

    @Test fun ownNetworkOwnershipProtectsAgainstDelayedServiceState() {
        assertEquals(TestNetworkSelection("cell", true),
            TestNetworkPolicy.select(own, listOf(own, cell), false))
    }

    @Test fun ownVpnAppExcludedStillPinsPhysicalNetwork() {
        assertEquals(TestNetworkSelection("wifi", true),
            TestNetworkPolicy.select(wifi, listOf(wifi, own), false))
    }

    @Test fun blockedDefaultDoesNotFallBackToAnAvailablePhysicalNetwork() {
        refused { TestNetworkPolicy.select(null, listOf(wifi, external), false) }
        refused { TestNetworkPolicy.select(external.copy(usable = false), listOf(wifi), false) }
        refused { TestNetworkPolicy.select(null, listOf(wifi), true) }
    }

    @Test fun ownVpnWithoutPhysicalExitDoesNotFallBackIntoTun() {
        refused { TestNetworkPolicy.select(own, listOf(own), true) }
    }

    @Test fun vpnRevokeOwnershipRaceFailsInsteadOfBypassingNewExternalVpn() {
        refused { TestNetworkPolicy.select(external, listOf(external, wifi), true) }
    }

    @Test fun defaultChangeFailsButTheNextTestCanUseTheNewNetwork() {
        val before = TestNetworkPolicy.select(wifi, listOf(wifi, cell), false)
        val after = TestNetworkPolicy.select(cell, listOf(wifi, cell), false)
        refused { TestNetworkPolicy.checkPinned(before, after) }
        TestNetworkPolicy.checkPinned(after, after)
    }

    @Test fun externalVpnStartStopOrPerAppRuleChangeInvalidatesCurrentTest() {
        val physical = TestNetworkPolicy.select(wifi, listOf(wifi), false)
        val vpn = TestNetworkPolicy.select(external, listOf(wifi, external), false)
        refused { TestNetworkPolicy.checkPinned(physical, vpn) }
        refused { TestNetworkPolicy.checkPinned(vpn, physical) }
        TestNetworkPolicy.checkPinned(physical, TestNetworkPolicy.select(wifi, listOf(wifi), false))
    }

    @Test fun ownVpnModeTransitionInvalidatesEvenWhenPhysicalNetworkIsUnchanged() {
        val idle = TestNetworkPolicy.select(wifi, listOf(wifi), false)
        val connected = TestNetworkPolicy.select(own, listOf(own, wifi), true)
        refused { TestNetworkPolicy.checkPinned(idle, connected) }
        refused { TestNetworkPolicy.checkPinned(connected, idle) }
    }
}
