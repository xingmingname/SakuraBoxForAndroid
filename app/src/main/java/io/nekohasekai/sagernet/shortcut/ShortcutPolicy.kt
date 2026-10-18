package io.nekohasekai.sagernet.shortcut

/** These three broadcasts are protected by Android, unlike ordinary action names.
 * Explicit broadcasts bypass Manifest intent filters, so the receiver must check. */
object ProtectedVpnBootActions {
    fun accepts(action: String?, sdk: Int) = when (action) {
        "android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED" -> true
        "android.intent.action.LOCKED_BOOT_COMPLETED" -> sdk >= 24
        else -> false
    }
}

/** These actions describe a command, never the caller's authority. */
enum class ShortcutOperation(val action: String) {
    TOGGLE("io.nekohasekai.sagernet.shortcut.TOGGLE"),
    ENABLE("io.nekohasekai.sagernet.shortcut.ENABLE"),
    DISABLE("io.nekohasekai.sagernet.shortcut.DISABLE");

    companion object {
        fun fromAction(action: String?) = entries.singleOrNull { it.action == action }
    }
}

data class ShortcutRequest(val operation: ShortcutOperation, val profileId: Long? = null) {
    companion object {
        fun parse(operation: ShortcutOperation?, hasProfile: Boolean, profile: Any?): ShortcutRequest? {
            if (operation == null) return null
            if (!hasProfile) return ShortcutRequest(operation)
            if (operation != ShortcutOperation.TOGGLE || profile !is Long || profile <= 0) return null
            return ShortcutRequest(operation, profile)
        }
    }
}

/** A fresh, visible user decision is required for every exported-entry invocation.
 * Never persisted to savedInstanceState or reconstructed from Intent extras. */
class ShortcutConsent(private val request: ShortcutRequest) {
    private var closed = false

    @Synchronized
    fun confirm(resumed: Boolean, focused: Boolean, unlocked: Boolean): ShortcutRequest? {
        if (closed) return null
        closed = true
        return request.takeIf { resumed && focused && unlocked }
    }

    @Synchronized
    fun cancel() { closed = true }
}

interface ShortcutEffects {
    val selectedProfile: Long
    fun profileExists(id: Long): Boolean
    fun selectProfile(id: Long)
    fun start()
    fun stop()
    fun reload()
}

/** Only used behind the non-exported Activity boundary, after platform delegation
 * or explicit user consent. At most one service callback may execute a command. */
class ShortcutExecution(private val request: ShortcutRequest) {
    private var consumed = false

    @Synchronized
    fun execute(canStop: Boolean, stopped: Boolean, effects: ShortcutEffects) {
        if (consumed) return
        consumed = true
        val profile = request.profileId
        if (profile != null && (profile <= 0 || request.operation != ShortcutOperation.TOGGLE ||
                    !effects.profileExists(profile))) return
        when (request.operation) {
            ShortcutOperation.ENABLE -> if (stopped) effects.start()
            ShortcutOperation.DISABLE -> if (canStop) effects.stop()
            ShortcutOperation.TOGGLE -> when {
                canStop -> if (profile == null || profile == effects.selectedProfile) effects.stop()
                    else { effects.selectProfile(profile); effects.reload() }
                stopped -> { if (profile != null) effects.selectProfile(profile); effects.start() }
            }
        }
    }
}
