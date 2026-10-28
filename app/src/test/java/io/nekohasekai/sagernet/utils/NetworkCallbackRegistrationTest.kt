package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

class NetworkCallbackRegistrationTest {
    @Test fun failedRegistrationNeverUnregistersAndNextStartRecovers() {
        var registrations = 0; var removals = 0; var warnings = 0
        val owner = NetworkCallbackRegistration({ if (++registrations == 1) error("synthetic registration failure") },
            { removals++ }, { warnings++ })
        owner.start(); assertTrue(owner.fallback); owner.stop(); assertEquals(0, removals)
        owner.start(); assertFalse(owner.fallback); owner.stop()
        assertEquals(2, registrations); assertEquals(1, removals); assertEquals(1, warnings)
    }
    @Test fun repeatedStartAndStopOwnExactlyOneCallback() {
        var active = 0; var registrations = 0
        val owner = NetworkCallbackRegistration({ assertEquals(0, active++); registrations++ },
            { assertEquals(1, active--) }, { throw AssertionError(it) })
        repeat(20) { owner.start(); owner.start(); owner.stop(); owner.stop() }
        assertEquals(0, active); assertEquals(20, registrations)
    }
    @Test fun ambiguousUnregisterFailureRetainsOwnershipAndRetriesWithoutLeaking() {
        var active = 0; var fail = true; var registrations = 0
        val owner = NetworkCallbackRegistration({ assertEquals(0, active++); registrations++ }, {
            if (fail) throw SecurityException("synthetic platform failure")
            assertEquals(1, active--)
        }, {})
        owner.start(); owner.stop(); assertTrue(owner.fallback)
        owner.start(); assertEquals(1, registrations); assertEquals(1, active)
        fail = false; owner.start(); assertFalse(owner.fallback)
        assertEquals(2, registrations); assertEquals(1, active)
        owner.stop(); assertEquals(0, active)
    }
    @Test fun alreadyRemovedCallbackDoesNotKillLaterListening() {
        var registrations = 0; var removals = 0
        val owner = NetworkCallbackRegistration({ registrations++ }, {
            if (++removals == 1) throw IllegalArgumentException("already removed")
        }, {})
        owner.start(); owner.stop(); owner.stop(); owner.start(); owner.stop()
        assertEquals(2, registrations); assertEquals(2, removals)
    }
}
