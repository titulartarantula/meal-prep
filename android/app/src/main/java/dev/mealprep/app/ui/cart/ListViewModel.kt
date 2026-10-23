package dev.mealprep.app.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.Extra
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.data.api.Staple
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.WeekOption
import dev.mealprep.app.ui.common.weekOptions
import dev.mealprep.app.work.JobWatcher
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer

data class ListState(
    val weeks: Set<LocalDate> = emptySet(),
    val options: List<WeekOption> = emptyList(),
    val items: List<ListItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val building: Boolean = false,
    val draftId: Int? = null,
    /** Weekly staples (milk, eggs …) in the household's order; [ticked] ones are merged into [items] by the server. */
    val staples: List<Staple> = emptyList(),
    val ticked: Set<Int> = emptySet(),
    val stapleError: String? = null,
    /** A cart already made for the first chosen week (ready to review or sent), so the list can lead back to it. */
    val existing: Draft? = null,
    /** Items added by search for the chosen weeks (always in the cart; delete to leave one out). */
    val extras: List<Extra> = emptyList(),
    val extraError: String? = null,
    /** Items in the fridge list (the saved copy): the "Use what's in the fridge" switch shows only with some. */
    val fridgeCount: Int = 0,
    /** Leave out what the fridge covers (on by default for this week and next; this phone remembers a choice). */
    val useFridge: Boolean = false,
) {
    /** Likely-on-hand items (oil, spices…) start unticked, in their own group (DESIGN Flow §4), and so do lines the
     *  fridge covers. A line that is only a staple is shown by its staple row instead. */
    val toBuy: List<ListItem> get() = items.filter { !it.likelyOnHand && !fromFridge(it) && !it.onlyStaple }
    val probablyHave: List<ListItem> get() = items.filter { (it.likelyOnHand || fromFridge(it)) && !it.onlyStaple }
    val neededCount: Int get() = items.count { it.needed } + extras.size
    val canBuild: Boolean get() = !loading && !building && draftId == null && neededCount > 0
    /** Added items go to the earliest chosen week. */
    val addWeek: LocalDate? get() = weeks.minOrNull()
    /** The list has staples but no recipe lines. */
    val onlyStaples: Boolean get() = !loading && items.isNotEmpty() && items.all { it.onlyStaple }
}

/** "Last bought 6 days ago" from a staple's ISO date (sent carts only, server side). */
fun lastBoughtText(lastBought: String?, today: LocalDate): String {
    val d = lastBought?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return "Not bought through the app yet"
    val days = ChronoUnit.DAYS.between(d, today).coerceAtLeast(0)
    return when {
        days == 0L -> "Last bought today"
        days == 1L -> "Last bought yesterday"
        days < 14 -> "Last bought $days days ago"
        else -> "Last bought ${days / 7} weeks ago"
    }
}

/** "1 pack", "2 packs", "2 L" — a staple without a unit counts packs. */
fun stapleAmount(s: Staple): String = if (s.unit == null) {
    val n = s.qty ?: 1.0
    "${qty(n)} " + if (n == 1.0) "pack" else "packs"
} else amountText(s.qty, s.unit) ?: s.unit

/** The fridge says little about weeks 3–4 out: subtract it by default only when this week or next is chosen. */
fun fridgeByDefault(weeks: Set<LocalDate>, today: LocalDate): Boolean {
    val cur = Weeks.weekStart(today)
    return weeks.any { it == cur || it == cur.plusWeeks(1) }
}

/** Selected weeks also appear in the picker when they're outside its 4 weeks (a far-off default, or a later week
 *  planned before the horizon), so they can be unticked; nothing else beyond the horizon is offered. */
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
    /** The shopper's own ticks, kept when the list is reloaded (another week, a staple ticked). */
    private val overrides = mutableMapOf<String, Boolean>()
    private var started = false
    /** The weeks the fridge switch was last set for (a new choice of weeks re-reads it). */
    private var fridgeAsked: Set<LocalDate>? = null

    init {
        viewModelScope.launch {
            val t = today()
            val weeks = initial.map(Weeks::weekStart).ifEmpty {
                listOf((repo.defaultCartWeek() as? ApiResult.Ok)?.value?.let(Weeks::weekStart) ?: Weeks.upcomingSunday(t))
            }.toSet()
            val options = weekOptions(t, repo.weeks(t, Weeks.HORIZON).value)
            val fridge = repo.fridge().value.orEmpty().count { i -> i.qty == null || i.qty > 0 }
            _state.update { it.copy(weeks = weeks, options = withSelected(options, weeks, t), fridgeCount = fridge) }
            loadStaples()
            started = true
            load()
        }
    }

    /** Weekly staples start ticked ("sometimes we get those at Costco": untick what isn't needed this week). */
    private suspend fun loadStaples() {
        val r = repo.staples()
        val list = r.value
        _state.update { s ->
            if (list == null) {
                // A server without staples (404) just has no section.
                val hide = (r.error as? ApiError.Http)?.code == 404
                s.copy(staples = emptyList(), ticked = emptySet(), stapleError = if (hide) null else r.error?.let { "Couldn't load the staples: ${it.userMessage()}" })
            } else {
                val known = s.staples.map { it.id }.toSet()
                // Keep this visit's ticks; a staple added meanwhile starts ticked if it's weekly.
                val ticked = list.filter { if (it.id in known) it.id in s.ticked else it.weekly }.map { it.id }.toSet()
                s.copy(staples = list, ticked = ticked, stapleError = null)
            }
        }
    }

    /** Back from the Staples screen: re-read them and reload the list if that changed what's merged in. */
    fun refreshStaples() {
        if (!started || _state.value.building || _state.value.draftId != null) return
        viewModelScope.launch {
            val before = _state.value.let { s -> s.ticked to s.staples.filter { it.id in s.ticked } }
            loadStaples()
            val after = _state.value.let { s -> s.ticked to s.staples.filter { it.id in s.ticked } }
            if (before != after) load()
        }
    }

    fun toggleStaple(id: Int) {
        if (_state.value.building || _state.value.draftId != null) return
        _state.update { s -> s.copy(ticked = if (id in s.ticked) s.ticked - id else s.ticked + id) }
        load()
    }

    fun toggleWeek(week: LocalDate) {
        if (_state.value.building || _state.value.draftId != null) return
        val w = _state.value.weeks.let { if (week in it) it - week else it + week }
        if (w.isEmpty()) return
        _state.update { it.copy(weeks = w) }
        load()
    }

    /** A new week choice: that choice's fridge switch (remembered, else the default), read before the list. */
    private suspend fun fridgeFor(weeks: Set<LocalDate>) {
        val on = fridgeChoice(weeks)
        _state.update { if (it.weeks == weeks) it.copy(useFridge = on) else it }
    }

    private fun fridgeKey(weeks: Set<LocalDate>) = "fridge-switch:" + weeks.sorted().joinToString(",")

    /** This phone's choice for these weeks, else the default. */
    private suspend fun fridgeChoice(weeks: Set<LocalDate>): Boolean =
        repo.getLocal(fridgeKey(weeks), Boolean.serializer()) ?: fridgeByDefault(weeks, today())

    /** "Use what's in the fridge": remembered on this phone for these weeks. */
    fun toggleFridge() {
        if (_state.value.building || _state.value.draftId != null) return
        fridgeAsked = _state.value.weeks
        val on = !_state.value.useFridge
        _state.update { it.copy(useFridge = on) }
        viewModelScope.launch { repo.putLocal(fridgeKey(_state.value.weeks), Boolean.serializer(), on) }
        load()
    }

    /** A newer week choice replaces an in-flight load, so a slow reply for the old choice can't overwrite the list. */
    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            if (fridgeAsked != _state.value.weeks) { fridgeFor(_state.value.weeks); fridgeAsked = _state.value.weeks }
            val s = _state.value
            val weeks = s.weeks.sorted()
            val staples = s.staples.filter { it.id in s.ticked }.map { it.id }
            val existing = repo.weekDraft(weeks.first()).value?.takeIf { it.status in setOf("building", "ready", "sent") }
            loadExtras(weeks)
            when (val r = repo.shoppingList(weeks, staples, fridge = s.useFridge && s.fridgeCount > 0)) {
                is ApiResult.Ok -> _state.update { it.copy(items = r.value.map { i -> overrides[i.key]?.let { n -> i.copy(needed = n) } ?: i },
                    loading = false, existing = existing,
                    error = if (r.value.isEmpty() && it.extras.isEmpty()) "Nothing planned for that week yet." else null) }
                is ApiResult.Err -> _state.update { it.copy(items = emptyList(), loading = false, existing = existing, error = r.error.userMessage()) }
            }
        }
    }

    fun retry() = load()

    private suspend fun loadExtras(weeks: List<LocalDate>) {
        when (val r = repo.extras(weeks)) {
            is ApiResult.Ok -> _state.update { it.copy(extras = r.value, extraError = null) }
            // A server without added items (404) just has none.
            is ApiResult.Err -> _state.update {
                it.copy(extras = emptyList(), extraError = if ((r.error as? ApiError.Http)?.code == 404) null
                    else "Couldn't load the added items: ${r.error.userMessage()}")
            }
        }
    }

    /** Back from Add an item: the added items again (either phone may have added some). */
    fun refreshExtras() {
        if (!started) return
        viewModelScope.launch {
            loadExtras(_state.value.weeks.sorted())
            _state.update { if (it.error == "Nothing planned for that week yet." && it.extras.isNotEmpty()) it.copy(error = null) else it }
        }
    }

    /** Takes an added item off the list (and out of a cart still under review). */
    fun removeExtra(id: Int) {
        val before = _state.value.extras
        _state.update { s -> s.copy(extras = s.extras.filter { it.id != id }, extraError = null) }
        viewModelScope.launch {
            (repo.deleteExtra(id) as? ApiResult.Err)?.let { e ->
                _state.update { it.copy(extras = before, extraError = "Couldn't remove it: ${e.error.userMessage()}") }
            }
        }
    }

    fun toggle(key: String) = _state.update { s ->
        s.copy(items = s.items.map { if (it.key == key) it.copy(needed = !it.needed).also { n -> overrides[key] = n.needed } else it })
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
