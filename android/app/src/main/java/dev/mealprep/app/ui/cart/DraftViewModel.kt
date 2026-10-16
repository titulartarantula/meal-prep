package dev.mealprep.app.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.userMessage
import kotlin.math.round
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A line that goes into the Loblaws cart on Send: kept, matched to a product, quantity > 0 (as the server counts). */
fun DraftLine.inCart(): Boolean = !removed && product != null && (quantity ?: 0) > 0

/** The server's estimated_total is from build time; recompute after edits (a price per 100 g counts grams). */
fun estimatedTotal(lines: List<DraftLine>): Double =
    round(lines.filter { it.inCart() }.sumOf { it.product!!.cost(it.quantity!!) ?: 0.0 } * 100) / 100

data class DraftState(
    val draft: Draft? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val busyLine: Int? = null,
    val sending: Boolean = false,
    val sentCartId: String? = null,
) {
    val total: Double get() = draft?.let { estimatedTotal(it.lines) } ?: 0.0
    val itemCount: Int get() = draft?.lines?.count { it.inCart() } ?: 0
    /** Sending an empty cart would only make an empty Loblaws cart and mark the week carted; a stale cart (the
     *  week's recipes changed since it was built) is an older cart: read-only, never sent (build a new one). */
    val canSend: Boolean get() = draft?.status == "ready" && draft.stale.not() && busyLine == null && !sending && itemCount > 0
    val editable: Boolean get() = draft?.status == "ready" && draft.stale.not() && busyLine == null && !sending
}

class DraftViewModel(private val repo: Repository, private val id: Int, private val pollMs: Long = 2_000) : ViewModel() {
    private val _state = MutableStateFlow(DraftState())
    val state = _state.asStateFlow()

    private var polling: Job? = null

    init { reload() }

    /** Until the draft leaves "building" (or the screen closes). Errors while polling are shown, then retried. */
    private suspend fun poll() {
        while (true) {
            when (val r = repo.draft(id)) {
                is ApiResult.Ok -> {
                    _state.update { it.copy(draft = r.value, loading = false, error = r.value.error) }
                    if (r.value.status != "building") return
                }
                is ApiResult.Err -> {
                    _state.update { it.copy(loading = false, error = r.error.userMessage()) }
                    if (r.error is ApiError.Http || r.error == ApiError.Unauthorized || r.error == ApiError.NotConfigured) return
                }
            }
            delay(pollMs)
        }
    }

    /** (Re)starts polling; a poll already running is replaced, never doubled. */
    fun reload() {
        polling?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        polling = viewModelScope.launch { poll() }
    }

    /** [q] = packs, or grams for a product sold by weight (the row steps by the product's interval). */
    fun setQuantity(line: DraftLine, q: Int) = edit(line.id) { repo.setLineQuantity(id, line.id, q.coerceAtLeast(0)) }
    fun setRemoved(line: DraftLine, removed: Boolean) = edit(line.id) { repo.setLineRemoved(id, line.id, removed) }
    /** Always buy this (or stop) for the line's product. */
    fun setPinned(line: DraftLine, pinned: Boolean) = edit(line.id) { repo.setPinned(id, line.id, pinned) }

    /** Back from the chooser (or the app resumed): load the draft again without a spinner; a build still being
     *  polled is left to its poll. */
    fun refresh() {
        if (polling?.isActive == true) return
        viewModelScope.launch {
            (repo.draft(id) as? ApiResult.Ok)?.let { r -> _state.update { it.copy(draft = r.value, loading = false) } }
        }
    }

    fun send() {
        if (!_state.value.canSend) return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            // Safe to repeat: the server sends a draft once and returns the same cart id after that.
            when (val r = repo.sendDraft(id)) {
                is ApiResult.Ok -> _state.update { it.copy(sending = false, sentCartId = r.value,
                    draft = it.draft?.copy(status = "sent", pcxCartId = r.value)) }
                is ApiResult.Err -> {
                    _state.update { it.copy(sending = false, error = sendMessage(r.error)) }
                    if (r.isConflict()) refreshAfterConflict()
                }
            }
        }
    }

    fun consumeSent() = _state.update { it.copy(sentCartId = null) }

    private fun edit(lineId: Int, call: suspend () -> ApiResult<DraftLine>) {
        if (!_state.value.editable) return
        _state.update { it.copy(busyLine = lineId, error = null) }
        viewModelScope.launch {
            when (val r = call()) {
                is ApiResult.Ok -> _state.update { s ->
                    s.copy(busyLine = null, draft = s.draft?.copy(lines = s.draft.lines.map { if (it.id == lineId) r.value else it }))
                }
                is ApiResult.Err -> {
                    _state.update { it.copy(busyLine = null, error = r.error.userMessage()) }
                    if (r.isConflict()) refreshAfterConflict()
                }
            }
        }
    }

    /** 409: the draft is no longer "ready" (sent from the other phone, or a server restart failed it), or the week's
     *  recipes changed since it was built (the server won't send a stale cart; the reload shows the banner). */
    private suspend fun refreshAfterConflict() {
        _state.update { it.copy(error = CHANGED) }
        (repo.draft(id) as? ApiResult.Ok)?.let { d ->
            _state.update { it.copy(draft = d.value, error = if (d.value.stale) null else it.error) }
        }
    }

    private fun ApiResult<*>.isConflict() = this is ApiResult.Err && (error as? ApiError.Http)?.code == 409

    companion object {
        const val CHANGED = "This cart changed on the server — reloaded."
        const val CHANGED_HERE = "This cart was sent or changed on the server — go back to see it."
        fun sendMessage(e: ApiError): String =
            if (e is ApiError.Http && e.code == 502) "Loblaws didn't take the cart just now. Try Send again in a minute."
            else e.userMessage()
    }
}
