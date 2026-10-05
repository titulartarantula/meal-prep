package dev.mealprep.app.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Staple
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StaplesState(
    val staples: List<Staple> = emptyList(),
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
    /** A change that didn't go through, said plainly. */
    val message: String? = null,
    val busy: Boolean = false,
)

/** What the edit dialog holds: text as typed. [id] null = a new staple. */
data class StapleForm(val id: Int? = null, val name: String = "", val amount: String = "1", val unit: String = "", val weekly: Boolean = true) {
    companion object {
        fun of(s: Staple) = StapleForm(s.id, s.name, s.qty?.let(::qty) ?: "", s.unit ?: "", s.weekly)
    }
}

/** "1", "2.5", "1,5" → number; blank → null (no amount); anything else is an error message. */
internal fun parseAmount(text: String): Result<Double?> {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return Result.success(null)
    val v = t.toDoubleOrNull()
    return if (v != null && v > 0 && v <= 1000) Result.success(v)
        else Result.failure(IllegalArgumentException("The amount must be a number, like 1 or 2.5."))
}

/** Off the home network a change can't be saved; say that rather than "can't reach the server". */
internal fun writeMessage(e: ApiError): String = when (e) {
    ApiError.Unreachable, ApiError.NotConfigured -> "Changing staples needs the home network (or WireGuard)."
    else -> e.userMessage()
}

class StaplesViewModel(private val repo: Repository) : ViewModel() {
    private val _state = MutableStateFlow(StaplesState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            val r = repo.staples()
            _state.update { it.copy(staples = r.value ?: it.staples, loading = false, offlineSince = r.offlineSince,
                error = if (r.value == null) r.error?.userMessage() else r.errorMessage) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Saves the form; returns false (and says why) when it isn't valid. */
    fun save(f: StapleForm): Boolean {
        val name = f.name.trim()
        if (name.isEmpty()) { _state.update { it.copy(message = "Give the staple a name.") }; return false }
        val qty = parseAmount(f.amount).getOrElse { e -> _state.update { it.copy(message = e.message) }; return false }
        val unit = f.unit.trim().ifBlank { null }
        write { if (f.id == null) repo.addStaple(name, qty, unit, f.weekly) else repo.editStaple(f.id, name, qty, unit, f.weekly) }
        return true
    }

    fun delete(id: Int) = write { repo.deleteStaple(id) }

    /** Up (-1) or down (+1) one place. */
    fun move(id: Int, by: Int) {
        val list = _state.value.staples
        val i = list.indexOfFirst { it.id == id }
        val to = i + by
        if (i < 0 || to !in list.indices) return
        // Show the new order at once; the reload afterwards has the server's.
        _state.update { s -> s.copy(staples = s.staples.toMutableList().apply { add(to, removeAt(i)) }) }
        write { repo.moveStaple(id, to) }
    }

    private fun write(call: suspend () -> ApiResult<*>) {
        _state.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val r = call()
            _state.update { it.copy(busy = false, message = (r as? ApiResult.Err)?.error?.let(::writeMessage)) }
            load()
        }
    }
}
