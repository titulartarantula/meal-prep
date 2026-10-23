package dev.mealprep.app.ui.fridge

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

data class ReviewState(
    val read: FridgeRead? = null,
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
    val ticks: Set<String> = emptySet(),
    val edits: Map<String, LineEdit> = emptyMap(),
    val saving: Boolean = false,
    /** Saved / discarded / sent again: what to say as the screen closes. */
    val done: String? = null,
    /** A save that didn't go through, said plainly. */
    val message: String? = null,
    /** The photos are still on the phone, so a failed read can be tried again. */
    val canRetry: Boolean = false,
)

class FridgeReviewViewModel(
    private val repo: Repository,
    private val readId: Int,
    private val saved: SavedStateHandle,
    /** Sends a photo folder again (ImportQueue.enqueueFridge). */
    private val resend: (File) -> Unit = {},
    private val pollMs: Long = 3_000,
) : ViewModel() {
    companion object {
        const val ALREADY_SAVED = "These changes were already saved (on the other phone?)."
        const val DISCARDED = "Discarded the changes from your photos."
        const val SENT_AGAIN = "Sending your fridge photos again…"
        private val editsSer = MapSerializer(String.serializer(), LineEdit.serializer())
    }

    private val _state = MutableStateFlow(ReviewState(
        ticks = saved.get<ArrayList<String>>("ticks")?.toSet().orEmpty(),
        edits = saved.get<String>("edits")?.let { runCatching { Http.json.decodeFromString(editsSer, it) }.getOrNull() }.orEmpty()))
    val state = _state.asStateFlow()
    private var poll: Job? = null

    init { load() }

    fun load() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (true) {
                val l = repo.fridgeRead(readId)
                val read = l.value
                val first = read != null && _state.value.read?.status != read.status && saved.get<ArrayList<String>>("ticks") == null
                _state.update { s ->
                    s.copy(read = read ?: s.read, loading = false, offlineSince = l.offlineSince,
                        error = if (read == null) l.error?.userMessage() else l.errorMessage,
                        ticks = if (first && read!!.status == "ready") ReviewLogic.ticks(read) else s.ticks)
                }
                if (first && read!!.status == "ready") keep()
                if (read?.status == "failed") _state.update { it.copy(canRetry = repo.fridgeBatch(readId) != null) }
                if (read?.status != "reading") break
                delay(pollMs)
            }
        }
    }

    private fun keep() {
        saved["ticks"] = ArrayList(_state.value.ticks)
        saved["edits"] = Http.json.encodeToString(editsSer, _state.value.edits)
    }

    fun toggle(pid: String) {
        _state.update { s -> s.copy(ticks = if (pid in s.ticks) s.ticks - pid else s.ticks + pid) }
        keep()
    }

    /** An edited line is also ticked (the reviewer wants it). */
    fun edit(pid: String, e: LineEdit) {
        _state.update { s -> s.copy(edits = s.edits + (pid to e), ticks = s.ticks + pid) }
        keep()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun save() {
        val s = _state.value
        val read = s.read ?: return
        if (s.saving || s.ticks.isEmpty()) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.applyRead(readId, ReviewLogic.lines(read, s.ticks, s.edits))) {
                is ApiResult.Ok -> finish(ReviewLogic.resultText(r.value.result))
                is ApiResult.Err -> if (r.error is ApiError.Http && (r.error as ApiError.Http).code == 409) finish(ALREADY_SAVED)
                    else _state.update { it.copy(saving = false, message = FridgeLogic.writeMessage(r.error)) }
            }
        }
    }

    fun discard() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            when (val r = repo.dismissRead(readId)) {
                is ApiResult.Ok -> finish(DISCARDED)
                is ApiResult.Err -> if (r.error is ApiError.Http && (r.error as ApiError.Http).code == 409) finish(ALREADY_SAVED)
                    else _state.update { it.copy(saving = false, message = FridgeLogic.writeMessage(r.error)) }
            }
        }
    }

    /** Try again after a failed read: the same photos go up again (a new read). */
    fun retry() {
        viewModelScope.launch {
            val dir = repo.fridgeBatch(readId) ?: return@launch
            resend(dir)
            _state.update { it.copy(done = SENT_AGAIN) }
        }
    }

    /** The photos aren't needed once the changes are saved or discarded. */
    private suspend fun finish(text: String) {
        repo.fridgeBatch(readId)?.deleteRecursively()
        repo.fridge()   // the saved copy shows the change away from home too
        _state.update { it.copy(saving = false, done = text) }
    }
}
