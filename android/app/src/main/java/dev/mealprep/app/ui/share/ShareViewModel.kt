package dev.mealprep.app.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.data.Repository
import dev.mealprep.app.ui.camera.PageStore
import dev.mealprep.app.ui.camera.PagesState
import dev.mealprep.app.work.ImportQueue
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareState(
    val input: ShareInput? = null,
    val message: String? = null,
    val queued: Boolean = false,
    val configured: Boolean = true,
    /** Cookbook photos: this recipe's folder in the app's storage, its pages in reading order, an optional title. */
    val dir: File? = null,
    val pages: PagesState = PagesState(),
    val title: String = "",
    /** Shared photos are still being copied in (and shrunk). */
    val copying: Boolean = false,
) {
    val isPhotos: Boolean get() = input is ShareInput.Photos || input is ShareInput.Pages
    val canConfirm: Boolean get() = configured && !queued && when {
        input is ShareInput.NytLink -> true
        isPhotos -> !copying && dir != null && pages.pages.isNotEmpty() && !pages.tooMany
        else -> false
    }
}

/**
 * The confirm screen for anything shared into the app (NYT link, gallery photos) or photographed in it: check the
 * pages, then hand the import to the background queue. It saves to Recipes only; weeks are planned from the library.
 * [copy] copies one shared image into a page file (shrunk and upright); shared content URIs are only readable
 * while this screen lives, so they are copied at once.
 */
class ShareViewModel(
    private val repo: Repository,
    private val imports: ImportQueue,
    private val pageStore: PageStore,
    private val copy: suspend (Uri, File) -> Unit,
    private val configured: () -> Boolean,
) : ViewModel() {
    companion object {
        const val NOT_A_RECIPE = "That isn't an NYT Cooking recipe link. Share a recipe from the NYT Cooking app or " +
            "site, or a photo of a cookbook page."
        const val TOO_MANY = "A recipe can have up to 10 pages. Delete the extras first."
        const val UNREADABLE = "Couldn't open those photos. Try sharing them again."
        fun someUnreadable(failed: Int, total: Int) = "Couldn't open $failed of the $total photos; the rest are below."
        const val SAVE_FAILED = "Couldn't get the pages ready. Try again."
    }

    private val _state = MutableStateFlow(ShareState())
    val state = _state.asStateFlow()
    private var copyJob: Job? = null

    fun start(input: ShareInput) {
        abandon()
        val notRecipe = input is ShareInput.NotARecipe
        _state.value = ShareState(input, if (notRecipe) NOT_A_RECIPE else null, configured = configured())
        if (notRecipe) return
        when (input) {
            is ShareInput.Photos -> copyIn(input.uris)
            is ShareInput.Pages -> { _state.update { it.copy(dir = input.dir, pages = PagesState(PageStore.pagesIn(input.dir))) }; checkCount() }
            else -> {}
        }
    }

    private fun copyIn(uris: List<Uri>) {
        val dir = pageStore.newBatch()
        _state.update { it.copy(copying = true, dir = dir) }
        copyJob = viewModelScope.launch {
            val files = mutableListOf<File>()
            for (u in uris) {
                val out = pageStore.newFile(dir)
                try {
                    copy(u, out); files += out
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    out.delete()
                }
            }
            val failed = uris.size - files.size
            _state.update { it.copy(copying = false, pages = PagesState(files), message = when {
                files.isEmpty() -> UNREADABLE
                failed > 0 -> someUnreadable(failed, uris.size)
                else -> it.message
            }) }
            checkCount()
        }
    }

    private fun checkCount() = _state.update {
        it.copy(message = if (it.pages.tooMany) TOO_MANY else it.message.takeUnless { m -> m == TOO_MANY })
    }

    fun setTitle(t: String) = _state.update { it.copy(title = t) }
    fun movePage(i: Int, by: Int) = _state.update { it.copy(pages = it.pages.move(i, by)) }
    fun removePage(i: Int) { _state.update { it.copy(pages = it.pages.remove(i)) }; checkCount() }

    /** Hands the import to the background queue; returns the work id (null if nothing to do). */
    fun confirm(): UUID? {
        val s = _state.value
        if (!s.canConfirm) return null
        val id = when (val input = s.input) {
            is ShareInput.NytLink -> imports.enqueueLink(input.url)
            else -> {
                val dir = s.dir!!
                // The pages are numbered in the order shown; the folder then belongs to the import job.
                if (runCatching { pageStore.commitOrder(dir, s.pages.pages) }.isFailure) {
                    _state.update { it.copy(message = SAVE_FAILED) }
                    return null
                }
                imports.enqueuePhotos(dir, s.title.trim().ifBlank { null })
            }
        }
        _state.update { it.copy(queued = true) }
        return id
    }

    /** Pages that were never handed to an import are deleted (Cancel, Back, or a new share replacing this one). */
    private fun abandon() {
        copyJob?.cancel(); copyJob = null
        val s = _state.value
        if (!s.queued) s.dir?.let(pageStore::discard)
    }

    override fun onCleared() = abandon()
}
