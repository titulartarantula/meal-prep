package dev.mealprep.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.camera.RefPrompt
import dev.mealprep.app.ui.camera.refPrompt
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import dev.mealprep.app.work.ImportQueue
import dev.mealprep.app.work.ImportWorker
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeekUi(
    val week: LocalDate,
    val view: WeekView? = null,
    val strip: StatusStrip = StatusStrip(false, false, false),
    val action: ContextAction = ContextAction.AddRecipes,
    /** The week's sent cart draft, so "Cart sent" can reopen it (Open in Loblaws again). */
    val sentDraftId: Int? = null,
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
)

class HomeViewModel(
    private val repo: Repository,
    queue: ImportQueue,
    private val today: () -> LocalDate = LocalDate::now,
    jobs: Flow<List<ImportJob>> = importJobs(queue),
    /** Dismissed import cards, shared with the Recipes tab (AppGraph.hiddenImports). */
    hidden: MutableStateFlow<Set<UUID>> = MutableStateFlow(emptySet()),
) : ViewModel() {
    private val weeks = mutableMapOf<LocalDate, MutableStateFlow<WeekUi>>()   // main thread only
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val loads = mutableMapOf<LocalDate, Job>()   // one in-flight load per week; a newer one cancels the older
    private val pending = mutableMapOf<LocalDate, Int>()   // writes in flight per week; loads wait for them

    // A finished import from 0.4.1 and earlier also added the recipe to a week: show it there.
    private val feed = ImportFeed(repo, queue, viewModelScope, jobs, hidden) { j ->
        j.output.getString(ImportWorker.OUT_WEEK)?.let { refresh(LocalDate.parse(it)) }
    }
    val imports: StateFlow<List<ImportUi>> = feed.cards

    /** Weeks after this one the pager reaches (Weeks.weeksAhead): 3, more only for later weeks that have recipes. */
    private val _ahead = MutableStateFlow(Weeks.HORIZON - 1)
    val ahead: StateFlow<Int> = _ahead.asStateFlow()

    private var aheadJob: Job? = null

    init { refreshAhead() }

    /** Re-reads which weeks after the horizon already have recipes (the saved copy will do offline). */
    fun refreshAhead() {
        aheadJob?.cancel()   // an older answer must not land after a newer one
        aheadJob = viewModelScope.launch {
            val t = today()
            _ahead.value = Weeks.weeksAhead(t, repo.weeks(Weeks.afterHorizon(t), Weeks.LOOK_AHEAD).value)
        }
    }

    fun week(week: LocalDate): StateFlow<WeekUi> {
        val w = Weeks.weekStart(week)
        return weeks[w] ?: MutableStateFlow(WeekUi(w)).also { weeks[w] = it; refresh(w) }
    }

    fun refresh(week: LocalDate) {
        val w = Weeks.weekStart(week)
        val f = weeks[w] ?: return
        loads[w]?.cancel()
        loads[w] = viewModelScope.launch { val r = load(w); if ((pending[w] ?: 0) == 0) f.update(r) }
    }

    private suspend fun load(w: LocalDate): (WeekUi) -> WeekUi = coroutineScope {
        val entries = async { repo.week(w) }
        val summary = async { repo.weeks(w, 1) }
        val draft = async { repo.weekDraft(w) }
        val prep = async { repo.weekPrepPlan(w) }
        val e = entries.await()
        val list = e.value
        val carted = summary.await().value?.firstOrNull()?.carted == true
        val d = draft.await().value
        val p = prep.await().value
        val r: (WeekUi) -> WeekUi = { prev -> prev.copy(
            view = list?.let { weekView(w, it) } ?: prev.view,
            strip = statusStrip(list.orEmpty(), carted, p),
            action = contextAction(w, today(), list.orEmpty(), carted, d, p),
            sentDraftId = d?.takeIf { it.status == "sent" }?.id,
            loading = false,
            offlineSince = e.offlineSince,
            error = if (list == null) e.error?.userMessage() else e.errorMessage,
        ) }
        r
    }

    fun place(entry: PlanEntry, day: Int?) =
        mutate(entry, { es -> es.map { if (it.id == entry.id) it.copy(day = day) else it } }) { repo.placeEntry(entry.id, day) }

    fun scale(entry: PlanEntry, multiplier: Double) =
        mutate(entry, { es -> es.map { if (it.id == entry.id) it.copy(multiplier = multiplier) else it } }) { repo.scaleEntry(entry.id, multiplier) }

    fun remove(entry: PlanEntry) = mutate(entry, { es -> es.filter { it.id != entry.id } }) { repo.removeEntry(entry.id) }

    /** Show the change at once; on failure say why; the refresh afterwards puts the week back. */
    private fun mutate(entry: PlanEntry, optimistic: (List<PlanEntry>) -> List<PlanEntry>, call: suspend () -> ApiResult<*>) {
        val w = Weeks.weekStart(LocalDate.parse(entry.week))
        val f = weeks[w] ?: return
        loads.remove(w)?.cancel()   // an older load must not overwrite this change
        pending[w] = (pending[w] ?: 0) + 1
        f.value.view?.let { v -> f.update { it.copy(view = weekView(w, optimistic(v.all))) } }
        viewModelScope.launch {
            val r = try { call() } finally { pending[w] = (pending[w] ?: 1) - 1 }
            if (r is ApiResult.Err) _message.value = r.error.userMessage()
            refresh(w)   // the server's answer (or, offline, the last saved copy) replaces any optimistic guess
        }
    }

    fun clearMessage() { _message.value = null }

    /** For the entry dialog: the recipe's first referenced page without a photo ("Add photo of p.191"). */
    suspend fun refPromptFor(recipeId: Int): RefPrompt? = repo.recipe(recipeId).value?.let(::refPrompt)

    fun dismissImport(id: UUID) = feed.dismiss(id)

    /** Cancel on an import that is waiting for the home network. */
    fun cancelImport(id: UUID) = feed.cancel(id)

    fun retryImport(id: UUID) = feed.retry(id)
}
