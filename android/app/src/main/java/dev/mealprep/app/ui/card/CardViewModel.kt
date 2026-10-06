package dev.mealprep.app.ui.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private fun instant(s: String?) = s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

/**
 * The card's "Last time" notes. The server copies the recipe's rating notes into the card when the prep plan is
 * written, so a note added after that (another night of the same recipe rated since) is put first here, from the
 * recipe's ratings: "Oct 13: kids picked out the peppers". Notes from this night or later ones are left out.
 */
fun cardNotes(card: CookCard, ratings: RatingSummary?): List<String> {
    val written = instant(card.generatedAt) ?: return card.ratingNotes
    val newer = ratings?.notes.orEmpty().filter { n ->
        val at = instant(n.ratedAt)
        at != null && at.isAfter(written) && (card.date == null || n.date == null || n.date < card.date)
    }.map { n ->
        val day = n.date?.let { d -> runCatching { Weeks.shortDate(LocalDate.parse(d)) }.getOrDefault(d) } ?: "Earlier"
        "$day: ${n.note}"
    }
    return newer + card.ratingNotes.filter { it !in newer }
}

data class CardState(val card: CookCard? = null, val loading: Boolean = true, val offlineSince: Instant? = null, val message: String? = null)

class CardViewModel(private val repo: Repository, private val entryId: Int) : ViewModel() {
    companion object { const val NO_CARD = "No cook card yet — put this recipe on a night and write the week's prep plan." }

    private val _state = MutableStateFlow(CardState())
    val state = _state.asStateFlow()
    private var loader: Job? = null

    init { reload() }

    /** Reads the card (the saved copy offline). A card that was shown stays up if a later read fails without one. */
    fun reload() {
        loader?.cancel()
        loader = viewModelScope.launch {
            val r = repo.card(entryId)
            _state.update { s ->
                val card = r.value ?: s.card.takeIf { r.error != null }
                CardState(card, false, r.offlineSince, r.errorMessage ?: if (card == null) NO_CARD else null)
            }
            // Notes from ratings made since the card was written (the recipe's saved copy will do offline).
            val card = r.value ?: return@launch
            val notes = cardNotes(card, repo.recipe(card.recipeId).value?.ratings)
            if (notes != card.ratingNotes) _state.update { s -> s.card?.let { c -> s.copy(card = c.copy(ratingNotes = notes)) } ?: s }
        }
    }
}
