package io.nekohasekai.sagernet.shortcut

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShortcutPolicyTest {
    @Test fun onlySystemProtectedBootActionsAccepted() {
        for (sdk in listOf(21, 22, 23, 24, 25, 26, 35, 36)) {
            listOf("BOOT_COMPLETED", "MY_PACKAGE_REPLACED").forEach {
                assertTrue(ProtectedVpnBootActions.accepts("android.intent.action.$it", sdk))
            }
            assertEquals(sdk >= 24, ProtectedVpnBootActions.accepts("android.intent.action.LOCKED_BOOT_COMPLETED", sdk))
        }
    }

    @Test fun explicitBroadcastWithForgedOrMissingActionRejectedBeforeEffects() {
        listOf(null, "", "android.intent.action.MAIN", "android.intent.action.PACKAGE_REPLACED",
            "io.nekohasekai.sagernet.shortcut.ENABLE", "moe.nb4a.debug.CLOSE", "BOOT_COMPLETED").forEach { action ->
            val effects = Effects()
            if (ProtectedVpnBootActions.accepts(action, 36)) effects.start()
            assertTrue(effects.calls.isEmpty())
            assertEquals(1L, effects.selectedProfile)
        }
    }

    private class Effects : ShortcutEffects {
        override var selectedProfile = 1L
        val calls = mutableListOf<String>()
        var exists = true
        override fun profileExists(id: Long) = exists
        override fun selectProfile(id: Long) { calls += "select:$id"; selectedProfile = id }
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
        override fun reload() { calls += "reload" }
    }

    @Test fun validCommandsAndMissingProfile() {
        ShortcutOperation.entries.forEach {
            assertEquals(ShortcutRequest(it), ShortcutRequest.parse(it, false, null))
        }
        assertEquals(ShortcutRequest(ShortcutOperation.TOGGLE, 2), ShortcutRequest.parse(ShortcutOperation.TOGGLE, true, 2L))
    }

    @Test fun forgedOrMalformedParametersFailClosed() {
        listOf(null, "2", 2, 0L, -1L, true, mapOf("authorized" to true)).forEach {
            assertNull(ShortcutRequest.parse(ShortcutOperation.TOGGLE, true, it))
        }
        assertNull(ShortcutRequest.parse(null, false, null))
        assertNull(ShortcutRequest.parse(ShortcutOperation.ENABLE, true, 2L))
        assertNull(ShortcutRequest.parse(ShortcutOperation.DISABLE, true, 2L))
        listOf(null, "android.intent.action.MAIN", "enable", "authorized").forEach {
            assertNull(ShortcutOperation.fromAction(it))
        }
    }

    @Test fun unconfirmedRequestsCannotReachExecutionForAnyVpnState() {
        for (operation in ShortcutOperation.entries) for (running in listOf(false, true)) {
            val effects = Effects()
            val consent = ShortcutConsent(ShortcutRequest(operation))
            consent.cancel()
            val grant = consent.confirm(true, true, true)
            grant?.let { ShortcutExecution(it).execute(running, !running, effects) }
            assertNull(grant)
            assertEquals(1L, effects.selectedProfile)
            assertTrue(effects.calls.isEmpty())
        }
    }

    @Test fun backgroundLockedAndUnfocusedDecisionsCannotAuthorize() {
        for (resumed in listOf(false, true)) for (focused in listOf(false, true)) for (unlocked in listOf(false, true)) {
            val consent = ShortcutConsent(ShortcutRequest(ShortcutOperation.DISABLE))
            val grant = consent.confirm(resumed, focused, unlocked)
            assertEquals(resumed && focused && unlocked, grant != null)
            // Failed decisions cannot become grants on a later callback.
            assertNull(consent.confirm(true, true, true))
        }
    }

    @Test fun explicitConsentPermitsOnlyFrozenRequestOnce() {
        val request = ShortcutRequest(ShortcutOperation.TOGGLE, 2)
        val consent = ShortcutConsent(request)
        assertSame(request, consent.confirm(true, true, true))
        assertNull(consent.confirm(true, true, true))
    }

    @Test fun repeatIntentCancellationAndRecreationDoNotRestoreGrant() {
        val old = ShortcutConsent(ShortcutRequest(ShortcutOperation.DISABLE))
        old.cancel()
        assertNull(old.confirm(true, true, true))
        val recreated = ShortcutConsent(ShortcutRequest(ShortcutOperation.DISABLE))
        // A new session has no transferable authorization; lifecycle must ask again.
        recreated.cancel()
        assertNull(recreated.confirm(true, true, true))
    }

    @Test fun enabledStartAndStoppedEnableArePreserved() {
        val effects = Effects()
        ShortcutExecution(ShortcutRequest(ShortcutOperation.ENABLE)).execute(false, true, effects)
        assertEquals(listOf("start"), effects.calls)
        effects.calls.clear()
        ShortcutExecution(ShortcutRequest(ShortcutOperation.ENABLE)).execute(true, false, effects)
        assertTrue(effects.calls.isEmpty())
    }

    @Test fun disableOnlyStopsStoppableState() {
        for (running in listOf(false, true)) {
            val effects = Effects()
            ShortcutExecution(ShortcutRequest(ShortcutOperation.DISABLE)).execute(running, !running, effects)
            assertEquals(if (running) listOf("stop") else emptyList<String>(), effects.calls)
        }
    }

    @Test fun genericTogglePreservesStartAndStop() {
        for (running in listOf(false, true)) {
            val effects = Effects()
            ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE)).execute(running, !running, effects)
            assertEquals(listOf(if (running) "stop" else "start"), effects.calls)
            assertEquals(1L, effects.selectedProfile)
        }
    }

    @Test fun profileTogglePreservesSelectStartAndSelectReload() {
        for (running in listOf(false, true)) {
            val effects = Effects()
            ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE, 2)).execute(running, !running, effects)
            assertEquals(listOf("select:2", if (running) "reload" else "start"), effects.calls)
            assertEquals(2L, effects.selectedProfile)
        }
    }

    @Test fun activeProfileToggleStopsWithoutMutation() {
        val effects = Effects()
        ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE, 1)).execute(true, false, effects)
        assertEquals(listOf("stop"), effects.calls)
        assertEquals(1L, effects.selectedProfile)
    }

    @Test fun deletedProfileFailsWithoutSelectingOrControllingService() {
        for (running in listOf(false, true)) {
            val effects = Effects().apply { exists = false }
            ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE, 2)).execute(running, !running, effects)
            assertTrue(effects.calls.isEmpty())
            assertEquals(1L, effects.selectedProfile)
        }
    }

    @Test fun invalidDirectRequestsCannotMutate() {
        for (operation in ShortcutOperation.entries) for (id in listOf(-1L, 0L)) {
            val effects = Effects()
            ShortcutExecution(ShortcutRequest(operation, id)).execute(true, false, effects)
            assertTrue(effects.calls.isEmpty())
        }
        for (operation in listOf(ShortcutOperation.ENABLE, ShortcutOperation.DISABLE)) {
            val effects = Effects()
            ShortcutExecution(ShortcutRequest(operation, 2L)).execute(true, false, effects)
            assertTrue(effects.calls.isEmpty())
        }
    }

    @Test fun idleOrStoppingStateDoesNotChangeProfileOrStart() {
        ShortcutOperation.entries.forEach {
            val effects = Effects()
            ShortcutExecution(ShortcutRequest(it)).execute(false, false, effects)
            assertTrue(effects.calls.isEmpty())
        }
        val effects = Effects()
        ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE, 2)).execute(false, false, effects)
        assertTrue(effects.calls.isEmpty())
    }

    @Test fun duplicateServiceCallbacksExecuteOnlyOnce() {
        val effects = Effects()
        val execution = ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE, 2))
        repeat(8) { execution.execute(false, true, effects) }
        assertEquals(listOf("select:2", "start"), effects.calls)
    }

    @Test fun failedEffectIsNotRetriedByCallback() {
        var count = 0
        val effects = object : ShortcutEffects {
            override val selectedProfile = 1L
            override fun profileExists(id: Long) = true
            override fun selectProfile(id: Long) = error("unexpected selection")
            override fun start() { count++; throw IllegalStateException("synthetic failure") }
            override fun stop() = error("unexpected stop")
            override fun reload() = error("unexpected reload")
        }
        val execution = ShortcutExecution(ShortcutRequest(ShortcutOperation.ENABLE))
        try { execution.execute(false, true, effects); fail() } catch (_: IllegalStateException) { }
        execution.execute(false, true, effects)
        assertEquals(1, count)
    }

    @Test fun concurrentConsentAndCallbacksAreOneShot() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val consent = ShortcutConsent(ShortcutRequest(ShortcutOperation.TOGGLE))
            val effects = Effects()
            val execution = ShortcutExecution(ShortcutRequest(ShortcutOperation.TOGGLE))
            val start = CountDownLatch(1)
            val grants = java.util.concurrent.atomic.AtomicInteger()
            val jobs = (1..16).map { pool.submit {
                start.await()
                if (consent.confirm(true, true, true) != null) grants.incrementAndGet()
                execution.execute(false, true, effects)
            } }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, grants.get())
            assertEquals(listOf("start"), effects.calls)
        } finally { pool.shutdownNow() }
    }
}
