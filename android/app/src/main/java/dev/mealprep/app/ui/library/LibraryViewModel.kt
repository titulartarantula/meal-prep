package dev.mealprep.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [server] is the GET /recipes sort; A–Z sorts the newest-first list on the phone. */
enum class LibrarySort(val label: String, val server: String) {
    NEWEST("Newest", "newest"), FAVOURITES("Favourites", "favourites"), AZ("A–Z", "newest"),
}

data class LibraryState(
    val all: List<Recipe> = emptyList(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.NEWEST,
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
) {
    val shown: List<Recipe> get() = filterRecipes(all, query, sort)
}

private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Every word typed must appear in the title (any case, accents optional: "crepe" finds "Crêpes"). */
fun filterRecipes(all: List<Recipe>, query: String, sort: LibrarySort): List<Recipe> {
    val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = all.filter { r -> fold(r.title).let { t -> words.all { it in t } } }
    return if (sort == LibrarySort.AZ) hits.sortedBy { fold(it.title) } else hits
}

/** "On the plan: this week, week of Oct 25" from the server's ISO Sundays; null when it isn't planned. */
fun plannedText(weeks: List<String>, today: LocalDate): String? {
    val labels = weeks.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        .map { Weeks.weekTitle(it, today).replaceFirstChar(Char::lowercase) }
    return if (labels.isEmpty()) null else "On the plan: " + labels.joinToString(", ")
}

class LibraryViewModel(private val repo: Repository) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    init { load() }

    /** Also on coming back to the tab: a recipe may have been added to a week, or shared from NYT. */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val r = repo.recipes(_state.value.sort.server)
            _state.update { it.copy(all = r.value ?: it.all, loading = false, offlineSince = r.offlineSince,
                error = if (r.value == null) r.error?.userMessage() else r.errorMessage) }
        }
    }

    private var resumed = false

    /** Coming back to the tab (not the first show, which [init] covers) reloads. */
    fun onResume() { if (resumed) load() else resumed = true }

    fun search(q: String) = _state.update { it.copy(query = q) }

    fun sort(s: LibrarySort) {
        val before = _state.value.sort
        _state.update { it.copy(sort = s) }
        if (s.server != before.server) load()
    }
}
