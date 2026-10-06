package dev.mealprep.app.ui.rating

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.common.offlineSince
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Family 1–5 ("was it a hit?"), company yes/maybe/no ("would I make it for guests?"), optional note. One rating per
 *  time it's cooked (plan entry); an already rated night opens with its rating filled in. */
data class RatingState(
    val title: String = "", val night: String = "",
    val family: Int? = null, val company: String? = null, val note: String = "",
    /** The night already has a rating (Save says Update; Remove rating shows). */
    val existing: Boolean = false,
    /** The recipe's ratings so far (all the times it was cooked), for "So far" and earlier notes. */
    val summary: RatingSummary? = null,
    val loading: Boolean = true, val saving: Boolean = false, val saved: Boolean = false,
    val offlineSince: Instant? = null, val error: String? = null,
)

/** Off the home network a rating can't be saved; say so rather than "can't reach the server". */
internal fun ratingMessage(e: ApiError): String = when (e) {
    ApiError.Unreachable, ApiError.NotConfigured -> "Saving a rating needs the home network (or WireGuard). Try again at home."
    else -> e.userMessage()
}

class RatingViewModel(
    private val repo: Repository,
    private val entryId: Int,
    private val week: LocalDate,
    /** After a save or remove (the phone's reminders are re-planned: a rated dinner needs no "How was it?"). */
    private val afterChange: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(RatingState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val w = repo.week(week)
            val e = w.value?.firstOrNull { it.id == entryId }
            if (e == null) {
                _state.update { it.copy(loading = false, offlineSince = w.offlineSince,
                    error = if (w.value == null) w.error?.userMessage() else "That dinner isn't in the plan any more.") }
                return@launch
            }
            _state.update { it.copy(title = e.title ?: "this recipe", night = e.day?.let(Weeks::longDayLabel).orEmpty(),
                family = e.rating?.family, company = e.rating?.company, note = e.rating?.note.orEmpty(),
                existing = e.rating != null, loading = false, offlineSince = w.offlineSince) }
            repo.recipe(e.recipeId).value?.let { r -> _state.update { it.copy(summary = r.ratings) } }
        }
    }

    fun setFamily(n: Int) = _state.update { it.copy(family = n.coerceIn(1, 5), error = null) }
    /** Tapping the chosen verdict again clears it (company is optional). */
    fun setCompany(c: String) = _state.update { it.copy(company = if (it.company == c) null else c) }
    fun setNote(s: String) = _state.update { it.copy(note = s) }

    fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val family = s.family ?: run { _state.update { it.copy(error = "Pick 1 to 5 first.") }; return }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.setRating(entryId, family, s.company, s.note)) {
                is ApiResult.Ok -> { refreshCopies(); _state.update { it.copy(saving = false, saved = true, existing = true) }; afterChange() }
                is ApiResult.Err -> _state.update { it.copy(saving = false, error = ratingMessage(r.error)) }
            }
        }
    }

    fun clear() {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.deleteRating(entryId)) {
                is ApiResult.Ok -> {
                    refreshCopies()
                    _state.update { it.copy(saving = false, saved = true, existing = false, family = null, company = null, note = "") }
                    afterChange()
                }
                is ApiResult.Err -> _state.update { it.copy(saving = false, error = ratingMessage(r.error)) }
            }
        }
    }

    /** The week's saved copy carries the rating (the reminders and banner read it); re-read before saying done. */
    private suspend fun refreshCopies() { repo.week(week) }
}
