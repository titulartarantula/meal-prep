package dev.mealprep.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Sources
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import dev.mealprep.app.ui.home.ImportFeed
import dev.mealprep.app.ui.home.ImportJob
import dev.mealprep.app.ui.home.ImportUi
import dev.mealprep.app.ui.home.importJobs
import dev.mealprep.app.work.ImportQueue
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import dev.mealprep.app.work.WatchedImport
import kotlinx.coroutines.launch

/** [server] is the GET /recipes sort; A–Z sorts the newest-first list on the phone. */
enum class LibrarySort(val label: String, val server: String) {
    NEWEST("Newest", "newest"), FAVOURITES("Favourites", "favourites"), AZ("A–Z", "newest"),
}

data class LibraryState(
    val all: List<Recipe> = emptyList(),
    val query: String = "",
    val sort: LibrarySort = LibrarySort.NEWEST,
    /** Sources.ALL or a source key (Sources.key): NYT, one book or named other source, Unknown book, Other. */
    val source: String = Sources.ALL,
    /** Only recipes whose latest company verdict is "yes" (rated good for guests). */
    val company: Boolean = false,
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
) {
    val shown: List<Recipe> get() = filterRecipes(all, query, sort, source, company)
    /** The source filter's choices, from the recipes themselves (works offline too). */
    val sources: List<Sources.Option> get() = Sources.options(all)
}

private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** Every word typed must appear in the title (any case, accents optional: "crepe" finds "Crêpes"); [source] narrows to
 *  one source (Sources.key); [company] to recipes rated good for company. */
fun filterRecipes(all: List<Recipe>, query: String, sort: LibrarySort, source: String = Sources.ALL, company: Boolean = false): List<Recipe> {
    val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = all.filter { r ->
        (source == Sources.ALL || Sources.key(r) == source) && (!company || r.ratings.company == "yes") &&
            fold(r.title).let { t -> words.all { it in t } }
    }
    return if (sort == LibrarySort.AZ) hits.sortedBy { fold(it.title) } else hits
}

/** "On the plan: this week, week of Oct 25" from the server's ISO Sundays; null when it isn't planned. */
fun plannedText(weeks: List<String>, today: LocalDate): String? {
    val labels = weeks.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        .map { Weeks.weekTitle(it, today).replaceFirstChar(Char::lowercase) }
    return if (labels.isEmpty()) null else "On the plan: " + labels.joinToString(", ")
}

class LibraryViewModel(
    private val repo: Repository,
    queue: ImportQueue? = null,
    jobs: Flow<List<ImportJob>> = queue?.let(::importJobs) ?: emptyFlow(),
    /** Dismissed import cards, shared with This week (AppGraph.hiddenImports). */
    hidden: MutableStateFlow<Set<UUID>> = MutableStateFlow(emptySet()),
    /** Imports from a file being watched in the background (JobWatcher.imports). */
    watches: Flow<List<WatchedImport>> = emptyFlow(),
) : ViewModel() {
    private val _state = MutableStateFlow(LibraryState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null

    // A recipe saved in the background (shared, scanned, pasted link) or a page added: show it.
    private val feed = ImportFeed(repo, queue, viewModelScope, jobs, hidden) { load() }
    val imports: StateFlow<List<ImportUi>> = feed.cards
    fun dismissImport(id: UUID) = feed.dismiss(id)
    fun cancelImport(id: UUID) = feed.cancel(id)
    fun retryImport(id: UUID) = feed.retry(id)

    private var finishedImports: Set<Int>? = null

    /** Running imports from a file (a card each); one finishing reloads the library (its recipes are in). */
    val fileImports: StateFlow<List<WatchedImport>> = watches.onEach { ws ->
        val finished = ws.filter { !it.running }.map { it.jobId }.toSet()
        val before = finishedImports
        finishedImports = finished
        if (before != null && (finished - before).isNotEmpty()) load()
    }.map { ws -> ws.filter { it.running } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
    fun source(key: String) = _state.update { it.copy(source = key) }
    fun company(on: Boolean) = _state.update { it.copy(company = on) }

    fun sort(s: LibrarySort) {
        val before = _state.value.sort
        _state.update { it.copy(sort = s) }
        if (s.server != before.server) load()
    }
}
