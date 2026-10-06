package dev.mealprep.app.ui.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.ui.common.errorMessage
import dev.mealprep.app.ui.common.offlineSince
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        }
    }
}
