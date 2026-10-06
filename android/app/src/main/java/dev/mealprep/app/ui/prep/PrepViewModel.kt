package dev.mealprep.app.ui.prep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.data.api.PrepTask
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import dev.mealprep.app.work.JobWatcher
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Replace one task and recount the Sunday checklist (day-of tasks are listed but done on the night). */
fun PrepPlan.withTask(t: PrepTask): PrepPlan {
    val secs = sections.map { s -> s.copy(tasks = s.tasks.map { if (it.id == t.id) t else it }) }
    val sunday = secs.flatMap { it.tasks }.filter { it.shelfLife != "day_of" }
    return copy(sections = secs, checklist = checklist.copy(done = sunday.count { it.done }, total = sunday.size))
}

/** What the progress line says while the server writes: the plan first (one long AI call), then a card per night.
 *  The server's progress counts the plan as 1 of 1 + nights. */
fun buildingText(p: PrepPlan): String {
    val cards = (p.progress.total - 1).coerceAtLeast(0)
    return if (p.progress.done == 0 || cards == 0) "Writing the prep plan…"
    else "Writing cook cards: ${(p.progress.done - 1).coerceIn(0, cards)} of $cards"
}

data class PrepState(
    /** The week's newest plan, whatever its status. */
    val plan: PrepPlan? = null,
    /** The last ready plan, shown while a newer one is being written or after it failed. */
    val previous: PrepPlan? = null,
    val loading: Boolean = true,
    val offlineSince: Instant? = null,
    val error: String? = null,
    val starting: Boolean = false,
    /** The server couldn't be asked and there is no saved copy: whether the week has a plan is unknown. */
    val unknown: Boolean = false,
) {
    val shown: PrepPlan? get() = plan?.takeIf { it.status == "ready" } ?: previous
    /** Writing a new plan throws away this one's ticks, so the screen asks first. */
    val hasTicks: Boolean get() = shown?.sections?.any { s -> s.tasks.any { it.done } } == true
}

class PrepViewModel(
    private val repo: Repository,
    private val jobs: JobWatcher,
    private val week: LocalDate,
    private val pollMs: Long = 5_000,
) : ViewModel() {
    private val _state = MutableStateFlow(PrepState())
    val state = _state.asStateFlow()
    private var loader: Job? = null
    /** Saving the cook cards for offline reading (one at a time). */
    private var cardsJob: Job? = null

    init { reload() }

    /** Reads the week's plan, then polls while it is being written. A newer reload replaces a running one. */
    fun reload() {
        loader?.cancel()
        loader = viewModelScope.launch {
            var first = true
            while (true) {
                val r = repo.weekPrepPlan(week)
                val p = r.value
                _state.update { s ->
                    val plan = p ?: s.plan.takeIf { r.error != null }
                    s.copy(plan = plan, loading = false, offlineSince = r.offlineSince, error = r.errorMessage,
                        unknown = plan == null && r.error != null && r.fetchedAt == null)
                }
                val prevId = p?.lastReadyId?.takeIf { p.status != "ready" && it != p.id }
                // Read once per reload (fresh ticks from the other phone), not on every poll while the new one is written.
                if (prevId == null) _state.update { it.copy(previous = null) }
                else if (first || _state.value.previous?.id != prevId) {
                    repo.prepPlanCopy(prevId).value?.let { prev -> _state.update { it.copy(previous = prev) } }
                }
                first = false
                if (r.error == null) _state.value.shown?.let(::saveCards)
                if (p?.status != "building" || r.error != null) return@launch
                delay(pollMs)
            }
        }
    }

    private fun saveCards(plan: PrepPlan) {
        if (cardsJob?.isActive == true) return
        cardsJob = viewModelScope.launch { repo.saveCards(plan) }
    }

    /** Write a plan for the week (first time, after a failure, or after the week changed). */
    fun start() {
        if (_state.value.starting || _state.value.plan?.status == "building") return
        _state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.startPrep(listOf(week))) {
                is ApiResult.Ok -> { jobs.watchPrep(r.value.id, week); _state.update { it.copy(starting = false) }; reload() }
                is ApiResult.Err -> _state.update { it.copy(starting = false, error = r.error.userMessage()) }
            }
        }
    }

    /** Tick or untick at once; the server's answer confirms it, a failure puts the box back and says why. */
    fun toggle(task: PrepTask) {
        val plan = _state.value.shown ?: return
        if (task.shelfLife == "day_of") return
        setTask(plan.id, task.copy(done = !task.done))
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            when (val r = repo.tickTask(plan.id, task.id, !task.done)) {
                is ApiResult.Ok -> setTask(plan.id, task.copy(done = r.value.done, doneAt = r.value.doneAt))
                is ApiResult.Err -> { setTask(plan.id, task); _state.update { it.copy(error = r.error.userMessage()) } }
            }
        }
    }

    /** Applies the task to whichever plan (newest or previous) it belongs to now — other ticks may have landed since. */
    private fun setTask(planId: Int, t: PrepTask) = _state.update { s ->
        when (planId) {
            s.plan?.id -> s.copy(plan = s.plan.withTask(t))
            s.previous?.id -> s.copy(previous = s.previous.withTask(t))
            else -> s
        }
    }
}
