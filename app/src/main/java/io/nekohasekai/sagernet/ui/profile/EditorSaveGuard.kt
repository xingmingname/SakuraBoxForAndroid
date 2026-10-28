package io.nekohasekai.sagernet.ui.profile

import kotlinx.coroutines.CancellationException

internal class EditorSaveGuard {
    private enum class Phase { IDLE, VALIDATING, COMMITTING, DISCARDED }
    private var phase = Phase.IDLE

    @Synchronized fun begin(): Boolean {
        if (phase != Phase.IDLE) return false
        phase = Phase.VALIDATING
        return true
    }

    @Synchronized fun beginCommit() {
        if (phase != Phase.VALIDATING) throw CancellationException("Editor save abandoned")
        phase = Phase.COMMITTING
    }

    @Synchronized fun complete() {
        if (phase != Phase.DISCARDED) phase = Phase.IDLE
    }

    @Synchronized fun discard(): Boolean {
        if (phase == Phase.COMMITTING) return false
        phase = Phase.DISCARDED
        return true
    }

    @Synchronized fun isCommitting() = phase == Phase.COMMITTING
    @Synchronized fun isDiscarded() = phase == Phase.DISCARDED
}
