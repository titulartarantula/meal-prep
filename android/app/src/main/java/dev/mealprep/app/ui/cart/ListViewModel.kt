package dev.mealprep.app.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.WeekOption
import dev.mealprep.app.ui.common.weekOptions
import dev.mealprep.app.work.JobWatcher
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListState(
    val weeks: Set<LocalDate> = emptySet(),
    val options: List<WeekOption> = emptyList(),
    val items: List<ListItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val building: Boolean = false,
    val draftId: Int? = null,
) {
    /** Likely-on-hand items (oil, spices…) start unticked, in their own group (DESIGN Flow §4). */
    val toBuy: List<ListItem> get() = items.filter { !it.likelyOnHand }
    val probablyHave: List<ListItem> get() = items.filter { it.likelyOnHand }
    val neededCount: Int get() = items.count { it.needed }
    val canBuild: Boolean get() = !loading && !building && draftId == null && items.any { it.needed }
}

/** Selected weeks first appear in the picker even when they're outside its 8 weeks (e.g. a far-off default). */
internal fun withSelected(options: List<WeekOption>, selected: Set<LocalDate>, today: LocalDate): List<WeekOption> {
    val missing = selected.filter { w -> options.none { it.week == w } }
        .map { WeekOption(it, Weeks.weekChoiceLabel(it, today), null) }
    return (options + missing).sortedBy { it.week }
}

class ListViewModel(
    private val repo: Repository,
    private val jobs: JobWatcher,
    initial: List<LocalDate>,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    private val _state = MutableStateFlow(ListState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val t = today()
            val weeks = initial.map(Weeks::weekStart).ifEmpty {
                listOf((repo.defaultCartWeek() as? ApiResult.Ok)?.value?.let(Weeks::weekStart) ?: Weeks.upcomingSunday(t))
            }.toSet()
            val options = weekOptions(t, repo.weeks(t, 8).value)
            _state.update { it.copy(weeks = weeks, options = withSelected(options, weeks, t)) }
            load()
        }
    }

    fun toggleWeek(week: LocalDate) {
        if (_state.value.building || _state.value.draftId != null) return
        val w = _state.value.weeks.let { if (week in it) it - week else it + week }
        if (w.isEmpty()) return
        _state.update { it.copy(weeks = w) }
        load()
    }

    /** A newer week choice replaces an in-flight load, so a slow reply for the old choice can't overwrite the list. */
    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val r = repo.shoppingList(_state.value.weeks.sorted())) {
                is ApiResult.Ok -> _state.update { it.copy(items = r.value, loading = false,
                    error = if (r.value.isEmpty()) "Nothing planned for that week yet." else null) }
                is ApiResult.Err -> _state.update { it.copy(items = emptyList(), loading = false, error = r.error.userMessage()) }
            }
        }
    }

    fun retry() = load()

    fun toggle(key: String) = _state.update { s ->
        s.copy(items = s.items.map { if (it.key == key) it.copy(needed = !it.needed) else it })
    }

    fun buildCart() {
        val s = _state.value
        if (!s.canBuild) return
        _state.update { it.copy(building = true, error = null) }
        viewModelScope.launch {
            val weeks = s.weeks.sorted()
            when (val r = repo.createDraft(s.items, weeks)) {
                is ApiResult.Ok -> { jobs.watchDraft(r.value.id, weeks.first()); _state.update { it.copy(building = false, draftId = r.value.id) } }
                is ApiResult.Err -> _state.update { it.copy(building = false, error = r.error.userMessage()) }
            }
        }
    }
}
