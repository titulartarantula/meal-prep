package dev.mealprep.app.ui.fridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Suggestion
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.home.freeNights
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SuggestState(
    val week: LocalDate,
    val nights: Int = 1,
    val people: Int = 4,
    val loading: Boolean = true,
    val result: Suggestion? = null,
    /** Picks ticked to add (all, as they come). */
    val ticked: Set<Int> = emptySet(),
    /** Picks left out with "Suggest others". */
    val excluded: Set<Int> = emptySet(),
    val adding: Boolean = false,
    val error: String? = null,
    /** Added: what to say as the screen closes. */
    val done: String? = null,
)

class SuggestViewModel(
    private val repo: Repository,
    week: LocalDate,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    companion object { const val MAX_NIGHTS = 7; const val MAX_PEOPLE = 20 }

    private val _state = MutableStateFlow(SuggestState(Weeks.weekStart(week)))
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val w = _state.value.week
            val free = freeNights(w, today(), repo.week(w).value.orEmpty())
            _state.update { it.copy(nights = free.coerceIn(1, MAX_NIGHTS)) }
            ask()
        }
    }

    fun today(): LocalDate = today.invoke()

    private fun ask() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            when (val r = repo.suggest(s.nights, s.people, s.week, s.excluded.toList())) {
                is ApiResult.Ok -> _state.update { it.copy(loading = false, result = r.value, ticked = r.value.picks.map { p -> p.recipeId }.toSet()) }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = message(r.error)) }
            }
        }
    }

    private fun message(e: ApiError) = when (e) {
        ApiError.Unreachable, ApiError.NotConfigured -> SuggestLogic.OFFLINE
        else -> e.userMessage()
    }

    fun setNights(n: Int) {
        if (n !in 1..MAX_NIGHTS || n == _state.value.nights) return
        _state.update { it.copy(nights = n) }; ask()
    }

    fun setPeople(n: Int) {
        if (n !in 1..MAX_PEOPLE || n == _state.value.people) return
        _state.update { it.copy(people = n) }; ask()
    }

    fun retry() = ask()

    fun toggle(id: Int) = _state.update { s -> s.copy(ticked = if (id in s.ticked) s.ticked - id else s.ticked + id) }

    /** Asks again without the picks that aren't ticked. */
    fun suggestOthers() {
        val s = _state.value
        val unticked = s.result?.picks.orEmpty().map { it.recipeId }.filter { it !in s.ticked }
        _state.update { it.copy(excluded = it.excluded + unticked) }
        ask()
    }

    /** Adds the ticked picks to the week (No night yet), never twice: the week is read again first. */
    fun addToWeek() {
        val s = _state.value
        if (s.adding || s.ticked.isEmpty()) return
        _state.update { it.copy(adding = true, error = null) }
        viewModelScope.launch {
            val now = repo.week(s.week)
            if (now.error != null) { _state.update { it.copy(adding = false, error = message(now.error)) }; return@launch }
            val there = now.value.orEmpty().map { it.recipeId }.toSet()
            val picks = s.result?.picks.orEmpty().filter { it.recipeId in s.ticked && it.recipeId !in there }
            var added = 0
            for (p in picks) {
                when (val r = repo.addToWeek(s.week, p.recipeId)) {
                    is ApiResult.Ok -> added++
                    is ApiResult.Err -> { _state.update { it.copy(adding = false, error = message(r.error)) }; return@launch }
                }
            }
            val n = s.ticked.size
            _state.update { it.copy(adding = false, done = "Added ${if (n == 1) "1 dinner" else "$n dinners"} to ${SuggestLogic.weekName(s.week, today())} — put them on nights there.") }
        }
    }
}
