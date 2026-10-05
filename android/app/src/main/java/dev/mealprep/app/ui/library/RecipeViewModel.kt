package dev.mealprep.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.WeekOption
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import dev.mealprep.app.ui.common.weekOptions
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The result of "Add to week": what to say, and the week to open if it worked. */
data class Added(val text: String, val week: LocalDate?, val ok: Boolean)

data class RecipeState(
    val recipe: Recipe? = null,
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
    val options: List<WeekOption> = emptyList(),
    val adding: Boolean = false,
    val added: Added? = null,
)

/** "the week of Oct 18" / "this week" for sentences. */
internal fun weekPhrase(week: LocalDate, today: LocalDate): String = when (Weeks.weeksBetween(today, week)) {
    0 -> "this week"
    1 -> "next week (${Weeks.shortDate(week)})"
    else -> "the week of ${Weeks.shortDate(week)}"
}

internal fun nightPhrase(day: Int?): String = if (day == null) "not on a night yet" else "on ${Weeks.longDayLabel(day)}"

/** Off the home network nothing can be added; say so rather than "can't reach the server". */
internal fun addMessage(e: ApiError): String = when (e) {
    ApiError.Unreachable, ApiError.NotConfigured -> "Adding to a week needs the home network (or WireGuard)."
    else -> e.userMessage()
}

class RecipeViewModel(
    private val repo: Repository,
    private val id: Int,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(RecipeState())
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { _state.update { it.copy(options = weekOptions(today(), repo.weeks(today(), 8).value)) } }
    }

    fun load() {
        viewModelScope.launch {
            val r = repo.recipe(id)
            _state.update { it.copy(recipe = r.value ?: it.recipe, loading = false, offlineSince = r.offlineSince,
                error = if (r.value == null) r.error?.userMessage() else r.errorMessage) }
        }
    }

    fun dismissAdded() = _state.update { it.copy(added = null) }

    /** Adds the recipe to [week] (and puts it on [day]) unless that week already has it — checked on the server
     *  first, since the other phone may have added it meanwhile; the server itself doesn't refuse duplicates. */
    fun addToWeek(week: LocalDate, day: Int?) {
        if (_state.value.adding) return
        val w = Weeks.weekStart(week)
        val t = today()
        _state.update { it.copy(adding = true, added = null) }
        viewModelScope.launch {
            val result = run {
                val now = repo.week(w)
                if (now.error != null) return@run Added(addMessage(now.error), null, false)
                now.value.orEmpty().firstOrNull { it.recipeId == id }?.let { e ->
                    return@run Added("It's already in ${weekPhrase(w, t)}, ${nightPhrase(e.day)}.", w, false)
                }
                val entry = when (val r = repo.addToWeek(w, id)) {
                    is ApiResult.Ok -> r.value
                    is ApiResult.Err -> return@run Added(addMessage(r.error), null, false)
                }
                if (day == null) return@run Added("Added to ${weekPhrase(w, t)}, in the tray until you put it on a night.", w, true)
                when (val p = repo.placeEntry(entry.id, day)) {
                    is ApiResult.Ok -> Added("Added to ${weekPhrase(w, t)}, ${nightPhrase(day)}.", w, true)
                    is ApiResult.Err -> Added("Added to ${weekPhrase(w, t)}, but couldn't put it on ${Weeks.longDayLabel(day)} " +
                        "(${p.error.userMessage()}). It's in the tray.", w, true)
                }
            }
            // Refresh the planned weeks and the saved copy of that week before saying it's done.
            val fresh = if (result.ok) { repo.week(w); repo.recipe(id).value } else null
            _state.update { it.copy(adding = false, added = result, recipe = fresh ?: it.recipe) }
        }
    }
}
