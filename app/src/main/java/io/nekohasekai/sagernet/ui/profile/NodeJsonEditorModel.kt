package io.nekohasekai.sagernet.ui.profile

import androidx.lifecycle.ViewModel
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.NodeJson
import io.nekohasekai.sagernet.fmt.NodeJsonProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class NodeJsonEditorModel : ViewModel() {
    data class State(val loaded: Boolean = false, val busy: Boolean = false, val error: String? = null, val saved: Boolean = false,
        val copied: Boolean = false, val choosing: Boolean = false, val runtimeWarning: Boolean = false)
    val state = MutableStateFlow(State())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveGuard = EditorSaveGuard()
    val committing get() = saveGuard.isCommitting()

    fun discard(): Boolean {
        if (!saveGuard.discard()) return false
        scope.cancel()
        return true
    }
    var text = ""
    var initial = ""
    var expected: NodeJson.State? = null
    var subscription = false
    private var pluginPorts = emptySet<Int>()
    var choices = emptyList<String>()
        private set
    val dirty get() = text != initial

    fun load(id: Long, mode: Int) {
        if (state.value.loaded || state.value.busy || state.value.choosing) return
        state.value = State(busy = true)
        scope.launch {
            try {
                val profile = SagerDatabase.proxyDao.getById(id) ?: error("JSON_NODE_REMOVED")
                expected = NodeJson.state(profile.requireBean())
                subscription = SagerDatabase.groupDao.getById(profile.groupId)?.type == GroupType.SUBSCRIPTION
                val preview = NodeJsonProfile.preview(profile, mode)
                text = preview.text; initial = text; pluginPorts = preview.pluginPorts
                choices = preview.choices
                state.value = State(loaded = choices.isEmpty(), choosing = choices.isNotEmpty())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = State(error = safeError(e)) }
        }
    }

    fun save(id: Long, mode: Int, copySubscription: Boolean) {
        if (!state.value.loaded || state.value.busy || !saveGuard.begin()) return
        val candidate = text
        state.value = State(loaded = true, busy = true)
        scope.launch {
            try {
                val saveContext = currentCoroutineContext()
                val saved = NodeJsonProfile.save(id, checkNotNull(expected), mode, candidate, copySubscription, pluginPorts) {
                    saveContext.ensureActive()
                    saveGuard.beginCommit()
                }
                state.value = State(loaded = true, saved = true, copied = saved.id != id, runtimeWarning = saved.runtimeWarning)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!saveGuard.isDiscarded()) state.value = State(loaded = true, error = safeError(e))
            } finally { saveGuard.complete() }
        }
    }

    fun clearError() { state.value = state.value.copy(error = null) }
    fun choose(index: Int) {
        text = choices[index]; initial = text; choices = emptyList()
        state.value = State(loaded = true)
    }
    private fun safeError(e: Exception) = when (e) {
        is NodeJson.Invalid -> "${e.kind}: ${e.message}"
        else -> if (e.message?.startsWith("JSON_") == true) e.message!! else "CORE: Configuration could not be checked; saved data was not changed"
    }
    override fun onCleared() { scope.cancel() }
}
