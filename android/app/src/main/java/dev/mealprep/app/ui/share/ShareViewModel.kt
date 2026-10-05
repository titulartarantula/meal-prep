package dev.mealprep.app.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.ui.common.WeekOption
import dev.mealprep.app.ui.common.weekOptions
import dev.mealprep.app.work.ImportQueue
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareState(
    val input: ShareInput? = null,
    val options: List<WeekOption> = emptyList(),
    val selected: LocalDate? = null,
    val message: String? = null,
    val queued: Boolean = false,
    val configured: Boolean = true,
    /** The /weeks lookup has finished (answered or failed); until then options carry no details. */
    val weeksChecked: Boolean = false,
) {
    val canConfirm: Boolean get() = configured && !queued && selected != null && input is ShareInput.NytLink
}

class ShareViewModel(
    private val repo: Repository,
    private val imports: ImportQueue,
    private val configured: () -> Boolean,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {
    companion object {
        const val NOT_A_RECIPE = "That isn't an NYT Cooking recipe link. Share a recipe from the NYT Cooking app or " +
            "site, or a photo of a cookbook page."
        const val PHOTOS_LATER = "Cookbook photos arrive in the next update."
    }

    private val _state = MutableStateFlow(ShareState())
    val state = _state.asStateFlow()

    fun start(input: ShareInput) {
        val t = today()
        val message = when (input) {
            is ShareInput.NotARecipe -> NOT_A_RECIPE
            is ShareInput.Photos, is ShareInput.Pages -> PHOTOS_LATER
            is ShareInput.NytLink -> null
        }
        _state.value = ShareState(input, weekOptions(t, null), Weeks.upcomingSunday(t), message, configured = configured())
        if (message != null) return
        viewModelScope.launch {
            val s = repo.weeks(t, 8).value
            _state.update { it.copy(options = if (s != null) weekOptions(t, s) else it.options, weeksChecked = true) }
        }
    }

    fun select(week: LocalDate) = _state.update { it.copy(selected = week) }

    /** Hands the import to the background queue; returns the work id (null if nothing to do). */
    fun confirm(): UUID? {
        val s = _state.value
        if (!s.canConfirm) return null
        val id = when (val input = s.input) {
            is ShareInput.NytLink -> imports.enqueueLink(input.url, s.selected!!)
            else -> return null
        }
        _state.update { it.copy(queued = true) }
        return id
    }
}
