package io.nekohasekai.sagernet.utils

internal class NetworkCallbackRegistration(
    private val register: () -> Unit,
    private val unregister: () -> Unit,
    private val warning: (Exception) -> Unit,
) {
    private var registered = false
    private var cleanupPending = false
    @Volatile var fallback = false
        private set

    fun start() {
        if (cleanupPending) stop()
        if (registered) return
        try {
            register()
            registered = true
            fallback = false
        } catch (e: Exception) {
            fallback = true
            warning(e)
        }
    }

    fun stop() {
        if (!registered) return
        try {
            unregister()
            registered = false
            cleanupPending = false
        } catch (e: IllegalArgumentException) {
            // Android uses this exception when the callback is already unregistered.
            registered = false
            cleanupPending = false
            warning(e)
        } catch (e: Exception) {
            // Retain ownership until cleanup can be retried; do not register a duplicate callback.
            cleanupPending = true
            fallback = true
            warning(e)
        }
    }
}
