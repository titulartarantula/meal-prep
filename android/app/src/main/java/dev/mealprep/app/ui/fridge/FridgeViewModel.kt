package dev.mealprep.app.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.FridgeItem
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FridgeState(
    val items: List<FridgeItem> = emptyList(),
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
    /** A change that didn't go through, said plainly. */
    val message: String? = null,
    val busy: Boolean = false,
    /** The newest photo read still reading or waiting for review (either phone's). */
    val read: FridgeRead? = null,
    /** Taken off the list on screen, waiting for its Undo snackbar ([FridgeViewModel.commitRemove] deletes it). */
    val removing: FridgeItem? = null,
    /** "Already in the fridge: 6 Eggs": the item already there, offered for editing. */
    val exists: ApiError.Exists? = null,
    /** Names the shopping list uses that contain what is typed. */
    val names: List<String> = emptyList(),
) {
    val shown: List<FridgeItem> get() = items.filter { it.id != removing?.id }
}

class FridgeViewModel(
    private val repo: Repository,
    private val today: () -> LocalDate = LocalDate::now,
    /** Outlives this ViewModel: a removal still waiting for its Undo snackbar is made final there. */
    private val later: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val debounceMs: Long = DEBOUNCE_MS,
) : ViewModel() {
    companion object { const val DEBOUNCE_MS = 300L }

    private val _state = MutableStateFlow(FridgeState())
    val state = _state.asStateFlow()
    private var lookup: Job? = null
    private var asked: String? = null

    init { load() }

    fun today(): LocalDate = today.invoke()

    fun load() {
        viewModelScope.launch {
            val r = repo.fridge()
            _state.update { it.copy(items = r.value ?: it.items, loading = false, offlineSince = r.offlineSince,
                error = if (r.value == null) r.error?.userMessage() else r.errorMessage) }
            val read = (repo.openFridgeRead() as? ApiResult.Ok)?.value
            if (r.error == null) _state.update { it.copy(read = read) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null, exists = null) }

    /** Suggestions as a name is typed: 300 ms after the last key, from 1 character; offline, quietly none. */
    fun typed(text: String) {
        val q = text.trim().lowercase()
        if (q == asked) return
        asked = q
        lookup?.cancel()
        if (q.isEmpty()) { _state.update { it.copy(names = emptyList()) }; return }
        lookup = viewModelScope.launch {
            delay(debounceMs)
            val found = (repo.fridgeNames(q) as? ApiResult.Ok)?.value.orEmpty()
            _state.update { it.copy(names = found) }
        }
    }

    fun clearNames() { lookup?.cancel(); asked = null; _state.update { it.copy(names = emptyList()) } }

    /** Saves the form; false (and why) when it isn't valid. A name already there comes back as [FridgeState.exists]. */
    fun save(f: FridgeForm): Boolean {
        val name = f.name.trim()
        if (name.isEmpty()) { _state.update { it.copy(message = "Give it a name, like eggs or spinach.") }; return false }
        if (name.length > 80) { _state.update { it.copy(message = "That name is too long (80 characters at most).") }; return false }
        val qty = FridgeLogic.parseAmount(f.amount).getOrElse { e -> _state.update { it.copy(message = e.message) }; return false }
        val unit = f.unit.trim().ifBlank { null }
        write {
            if (f.id == null) repo.addFridge(name, qty, unit, f.useSoon, f.useBy, f.fromList)
            else repo.editFridge(f.id, name, qty, unit, f.useSoon, f.useBy, f.fromList)
        }
        return true
    }

    /** "Still right": the amount is dated today. */
    fun confirm(id: Int) = write { repo.confirmFridge(id) }

    /** Off the list at once; deleted when the Undo snackbar goes ([commitRemove]) or the screen is left. */
    fun remove(item: FridgeItem) {
        commitRemove()
        _state.update { it.copy(removing = item) }
    }

    fun undoRemove() = _state.update { it.copy(removing = null) }

    fun commitRemove() {
        val item = _state.value.removing ?: return
        _state.update { it.copy(removing = null, items = it.items.filter { i -> i.id != item.id }) }
        write { repo.deleteFridge(item.id) }
    }

    override fun onCleared() {
        _state.value.removing?.let { item -> later.launch { repo.deleteFridge(item.id) } }
    }

    private fun write(call: suspend () -> ApiResult<*>) {
        _state.update { it.copy(busy = true, message = null, exists = null) }
        viewModelScope.launch {
            val r = call()
            val e = (r as? ApiResult.Err)?.error
            _state.update { it.copy(busy = false, exists = e as? ApiError.Exists,
                message = e?.takeUnless { x -> x is ApiError.Exists }?.let(FridgeLogic::writeMessage)) }
            load()   // the saved copy stays fresh
        }
    }
}
