package dev.mealprep.app.ui.exchange

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.ExportFile
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiResult
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Save to a file (the system's document picker) or Send… (the share sheet). */
enum class ExportAction { SAVE, SEND }

data class ExportState(
    /** The server is making the file ("Getting your recipes ready…"). */
    val busy: Boolean = false,
    val error: String? = null,
    /** The file, once made (kept for a second action while the dialog stays open). */
    val file: ExportFile? = null,
    /** What the screen should open next with [file]: the document picker or the share sheet (then [handled]). */
    val pending: ExportAction? = null,
    /** "Saved meal-prep-recipes-2026-10-06.json." once Save to a file worked. */
    val saved: String? = null,
    val last: ExportAction? = null,
)

/**
 * One recipe ([recipeId]) or the whole library (null) as a schema.org file, ratings and notes always included. The
 * server makes the file (one mapper, so no offline export); [write] copies it into the document the user chose.
 */
class ExportViewModel(
    private val repo: Repository,
    private val dir: File,
    private val recipeId: Int?,
    private val write: (File, Uri) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportState())
    val state = _state.asStateFlow()

    companion object {
        const val SAVE_FAILED = "Couldn't save the file there. Try again, or choose another place."
        fun savedText(name: String) = "Saved $name."
    }

    /** The dialog opened again: a fresh file (the recipes may have changed since). */
    fun reset() { if (!_state.value.busy) _state.value = ExportState() }

    fun start(action: ExportAction) {
        val s = _state.value
        if (s.busy) return
        if (s.file != null && s.file.file.exists()) { _state.update { it.copy(pending = action, last = action, error = null, saved = null) }; return }
        _state.update { it.copy(busy = true, error = null, saved = null, pending = null, last = action) }
        viewModelScope.launch {
            val r = if (recipeId == null) repo.exportLibrary(dir) else repo.exportRecipe(recipeId, dir)
            _state.update {
                when (r) {
                    is ApiResult.Ok -> it.copy(busy = false, file = r.value, pending = action)
                    is ApiResult.Err -> it.copy(busy = false, error = ExchangeText.exportError(r.error))
                }
            }
        }
    }

    /** Try again: the last action. */
    fun retry() = start(_state.value.last ?: ExportAction.SAVE)

    /** The screen opened the picker / share sheet. */
    fun handled() = _state.update { it.copy(pending = null) }

    /** The document the user chose in Save to a file (null = they backed out). */
    fun saveTo(uri: Uri?) {
        val f = _state.value.file ?: return
        if (uri == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { write(f.file, uri) }.isSuccess }
            _state.update { if (ok) it.copy(saved = savedText(f.name), error = null) else it.copy(error = SAVE_FAILED) }
        }
    }
}
