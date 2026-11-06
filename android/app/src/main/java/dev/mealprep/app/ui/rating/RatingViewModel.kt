package dev.mealprep.app.ui.rating

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.PersonRating
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.data.settings.Account
import dev.mealprep.app.ui.common.Access
import dev.mealprep.app.ui.common.Perm
import dev.mealprep.app.ui.common.offlineSince
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Someone this phone's person can rate for: themselves ([id] null, "Me") or a child profile. */
data class Rater(val id: Int?, val label: String)

/**
 * A person's 1–5 ("was it a hit?") and note for one time cooked (plan entry); everyone rates for themselves, and a
 * parent with rate.for_children can rate for a child profile ("Rating for"). Good for company is not part of a
 * rating: it is the recipe's one household Yes / No, chosen here by an Owner (recipe.company), read-only for others.
 */
data class RatingState(
    val title: String = "", val night: String = "",
    val family: Int? = null, val note: String = "",
    /** The recipe's Good for company verdict as shown (an Owner's pick, else the saved one): "yes" | "no" | null. */
    val company: String? = null,
    /** This person may set the verdict (Owners). */
    val canSetCompany: Boolean = false,
    /** Who this rating is for (null = yourself) and who can be picked (only shown with a child profile to rate for). */
    val who: Int? = null, val raters: List<Rater> = listOf(Rater(null, "Me")),
    /** The rater ([who]) already rated this night (Save says Update; Remove rating shows). */
    val existing: Boolean = false,
    /** Everyone else's ratings of this night. */
    val others: List<PersonRating> = emptyList(),
    /** Your member id (your own name says "you"). */
    val me: Int? = null,
    /** The recipe's ratings so far (all the times it was cooked), for "So far" and earlier notes. */
    val summary: RatingSummary? = null,
    val loading: Boolean = true, val saving: Boolean = false, val saved: Boolean = false,
    val offlineSince: Instant? = null, val error: String? = null,
) {
    /** "Your score" / "Pip's score". */
    val whose: String get() = if (who == null) "Your score" else "${raters.firstOrNull { it.id == who }?.label ?: "Their"}'s score"
}

/** Away from home a household-token phone can't save; a signed-in one can, so it only says it can't reach Meal Prep. */
internal fun ratingMessage(e: ApiError, signedIn: Boolean = false): String = when (e) {
    ApiError.Unreachable, ApiError.NotConfigured ->
        if (signedIn) "Can't reach Meal Prep. Check the phone's internet connection and try again."
        else "Saving a rating needs the home network (or WireGuard). Try again at home."
    else -> e.userMessage()
}

class RatingViewModel(
    private val repo: Repository,
    private val entryId: Int,
    private val week: LocalDate,
    /** Who is rating (GET /auth/me as saved): their member id and permissions. null = not known (nothing hidden). */
    account: Account? = null,
    private val signedIn: Boolean = false,
    /** After a save or remove (the phone's reminders are re-planned: a rated dinner needs no "How was it?"). */
    private val afterChange: () -> Unit = {},
) : ViewModel() {
    private val access = Access(account?.permissions, account?.memberId)
    private val _state = MutableStateFlow(RatingState(me = access.me, canSetCompany = access.can(Perm.RECIPE_COMPANY)))
    val state = _state.asStateFlow()
    private var entry: PlanEntry? = null
    private var recipeId: Int = 0
    /** The verdict as saved (the pick is sent only when it differs). */
    private var savedCompany: String? = null

    init {
        viewModelScope.launch {
            val w = repo.week(week)
            val e = w.value?.firstOrNull { it.id == entryId }
            if (e == null) {
                _state.update { it.copy(loading = false, offlineSince = w.offlineSince,
                    error = if (w.value == null) w.error?.userMessage() else "That dinner isn't in the plan any more.") }
                return@launch
            }
            entry = e; recipeId = e.recipeId
            _state.update { it.copy(title = e.title ?: "this recipe", night = e.day?.let(Weeks::longDayLabel).orEmpty(),
                loading = false, offlineSince = w.offlineSince) }
            show(null)
            repo.recipe(e.recipeId).value?.let { r ->
                savedCompany = r.ratings.company?.takeIf { it == "yes" || it == "no" }
                _state.update { it.copy(summary = r.ratings, company = savedCompany) }
            }
            if (access.can(Perm.RATE_FOR_CHILDREN)) repo.members().value?.filter { it.kind == "child" }?.let { kids ->
                if (kids.isNotEmpty()) _state.update { s -> s.copy(raters = listOf(Rater(null, "Me")) + kids.map { Rater(it.id, it.name) }) }
            }
        }
    }

    /** The rating [who] made of this night (null = yourself: my_rating, or `rating` from an older server). */
    private fun ratingOf(who: Int?): Pair<Int, String?>? {
        val e = entry ?: return null
        if (who == null) {
            val me = access.me
            if (me != null && e.ratings.isNotEmpty()) return e.ratings.firstOrNull { it.memberId == me }?.let { it.family to it.note }
            return (e.myRating ?: e.rating.takeIf { e.ratings.isEmpty() })?.let { it.family to it.note }
        }
        return e.ratings.firstOrNull { it.memberId == who }?.let { it.family to it.note }
    }

    private fun show(who: Int?) {
        val r = ratingOf(who)
        // Whose rating this is; not known yet (no /auth/me saved) = everyone's are listed.
        val rater = who ?: access.me
        val others = entry?.ratings.orEmpty().filter { rater == null || it.memberId != rater }
        _state.update { it.copy(who = who, family = r?.first, note = r?.second.orEmpty(), existing = r != null,
            others = others, error = null) }
    }

    /** "Rating for": switch to yourself or a child profile (their saved rating fills in). */
    fun setWho(who: Int?) { if (who != _state.value.who) show(who) }
    fun setFamily(n: Int) = _state.update { it.copy(family = n.coerceIn(1, 5), error = null) }
    /** Owners: Yes / No; choosing it again clears it (not decided). */
    fun setCompany(c: String) = _state.update { if (!it.canSetCompany) it else it.copy(company = if (it.company == c) null else c) }
    fun setNote(s: String) = _state.update { it.copy(note = s) }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val family = s.family ?: run { _state.update { it.copy(error = "Pick 1 to 5 first.") }; return }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val r = repo.setRating(entryId, family, s.note, s.who)
            if (r is ApiResult.Err) { _state.update { it.copy(saving = false, error = ratingMessage(r.error, signedIn)) }; return@launch }
            if (!saveCompany(s)) return@launch
            refreshCopies(); _state.update { it.copy(saving = false, saved = true, existing = true) }; afterChange()
        }
    }

    /** The verdict, only when an Owner changed it. false = it failed (said on screen; the rating itself is saved). */
    private suspend fun saveCompany(s: RatingState): Boolean {
        if (!s.canSetCompany || s.company == savedCompany || recipeId == 0) return true
        return when (val r = repo.setCompany(recipeId, s.company)) {
            is ApiResult.Ok -> { savedCompany = s.company; true }
            is ApiResult.Err -> {
                _state.update { it.copy(saving = false, existing = true,
                    error = "The rating is saved, but Good for company wasn't: " + ratingMessage(r.error, signedIn)) }
                false
            }
        }
    }

    fun clear() {
        if (_state.value.saving) return
        val who = _state.value.who
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.deleteRating(entryId, who)) {
                is ApiResult.Ok -> {
                    refreshCopies()
                    _state.update { it.copy(saving = false, saved = true, existing = false, family = null, note = "") }
                    afterChange()
                }
                is ApiResult.Err -> _state.update { it.copy(saving = false, error = ratingMessage(r.error, signedIn)) }
            }
        }
    }

    /** The week's saved copy carries the rating (the reminders and banner read it); re-read before saying done. */
    private suspend fun refreshCopies() { repo.week(week); if (recipeId != 0) repo.recipe(recipeId) }
}
