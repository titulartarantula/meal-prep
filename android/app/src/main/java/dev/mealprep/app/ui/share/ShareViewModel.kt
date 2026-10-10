package dev.mealprep.app.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.core.BookChoice
import dev.mealprep.app.core.BookSuggestion
import dev.mealprep.app.core.BookSuggestions
import dev.mealprep.app.core.Books
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.core.ShareParser
import dev.mealprep.app.core.Sources
import dev.mealprep.app.data.Repository
import dev.mealprep.app.ui.camera.PageStore
import dev.mealprep.app.ui.camera.PagesState
import dev.mealprep.app.ui.common.BookLookup
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
    /** Cookbook photos: the book (default: the last one used; empty = unknown; a picked suggestion also carries its
     *  author/ISBN) and page; [books] = the household's books, [found] = the server's book search for what is typed. */
    val choice: BookChoice = BookChoice(),
    val page: String = "",
    val books: List<BookSuggestion> = emptyList(),
    val found: List<BookSuggestion> = emptyList(),
    /** Book (default) or Other: a family recipe card and the like, with [otherName] (needed) and an optional [note];
     *  [others] = the household's names already used. */
    val kind: String = Sources.BOOK,
    val otherName: String = "",
    val note: String = "",
    val others: List<String> = emptyList(),
    /** Shared photos are still being copied in (and shrunk). */
    val copying: Boolean = false,
    /** Save was tapped with Other and no name: the Name field says what's missing. */
    val nameMissing: Boolean = false,
) {
    val book: String get() = choice.title
    val suggestions: BookSuggestions get() = Books.suggest(books, found, choice)
    val isPhotos: Boolean get() = input is ShareInput.Photos || input is ShareInput.Pages
    val canConfirm: Boolean get() = canSave && !(isPhotos && kind == Sources.OTHER && otherName.isBlank())
    /** Save is enabled: everything but an other source's name is there (a tap without the name says so). */
    val canSave: Boolean get() = configured && !queued && when {
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
    private val lastBook: () -> String? = { null },
    private val rememberBook: (String) -> Unit = {},
) : ViewModel() {
    companion object {
        const val NOT_A_RECIPE = "That isn't an NYT Cooking recipe link. Share a recipe from the NYT Cooking app or " +
            "site, or a photo of a cookbook page."
        /** A shared web link that isn't NYT Cooking: the app never fetches other sites, so a file has to bring it in. */
        const val NOT_NYT_LINK = "Only NYT Cooking links can be imported from a link. Save the recipe's web page " +
            "(or export it from the other app), then import the file: Recipes → More options → Import recipes from a file."
        const val TOO_MANY = "A recipe can have up to 10 pages. Delete the extras first."
        const val UNREADABLE = "Couldn't open those photos. Choose or share them again."
        fun someUnreadable(failed: Int, total: Int) = "Couldn't open $failed of the $total photos; the rest are below."
        const val SAVE_FAILED = "Couldn't get the pages ready. Try again."
    }

    private val _state = MutableStateFlow(ShareState())
    val state = _state.asStateFlow()
    private var copyJob: Job? = null
    /** Loading the household's books (tests wait for it before closing the cache). */
    internal var booksJob: Job? = null
    private val lookup = BookLookup(viewModelScope, { q -> repo.searchBooks(q, Books.LIMIT) })

    init {
        viewModelScope.launch { lookup.found.collect { f -> _state.update { it.copy(found = f) } } }
    }

    fun start(input: ShareInput) {
        abandon()
        lookup.typed("")   // a new share starts without the last one's search rows
        val notRecipe = input is ShareInput.NotARecipe
        val message = when {
            input is ShareInput.NotARecipe && ShareParser.hasLink(input.text) -> NOT_NYT_LINK
            notRecipe -> NOT_A_RECIPE
            else -> null
        }
        _state.value = ShareState(input, message, configured = configured())
        if (notRecipe) return
        when (input) {
            is ShareInput.Photos -> copyIn(input.uris)
            is ShareInput.Pages -> { _state.update { it.copy(dir = input.dir, pages = PagesState(PageStore.pagesIn(input.dir))) }; checkCount() }
            else -> {}
        }
        if (_state.value.isPhotos) {
            _state.update { it.copy(choice = BookChoice(lastBook().orEmpty())) }
            val previous = booksJob
            booksJob = viewModelScope.launch {   // the saved copy will do offline
                previous?.join()                 // one after the other: the newest list wins
                val sources = repo.sources().value.orEmpty()
                _state.update { it.copy(books = Books.yours(sources), others = Sources.otherNames(sources)) }
            }
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
    fun setBook(b: String) { _state.update { it.copy(choice = it.choice.typed(b)) }; lookup.typed(b) }
    fun pickBook(b: BookSuggestion) = _state.update { it.copy(choice = b.choice) }
    fun setPage(p: String) = _state.update { it.copy(page = p) }
    fun setKind(k: String) = _state.update { it.copy(kind = k) }
    fun setOtherName(n: String) = _state.update { it.copy(otherName = n) }
    fun setNote(n: String) = _state.update { it.copy(note = n) }
    fun movePage(i: Int, by: Int) = _state.update { it.copy(pages = it.pages.move(i, by)) }
    fun removePage(i: Int) { _state.update { it.copy(pages = it.pages.remove(i)) }; checkCount() }
    /** Undo a delete: the page's file is still in the folder until Save. */
    fun restorePage(i: Int, f: java.io.File) { _state.update { it.copy(pages = it.pages.restore(i, f)) }; checkCount() }

    /** Hands the import to the background queue; returns the work id (null if nothing to do). */
    fun confirm(): UUID? {
        val s = _state.value
        if (!s.canConfirm) {
            if (s.canSave) _state.update { it.copy(nameMissing = true) }   // only the name is missing
            return null
        }
        val id = when (val input = s.input) {
            is ShareInput.NytLink -> imports.enqueueLink(input.url)
            else -> {
                val dir = s.dir!!
                // The pages are numbered in the order shown; the folder then belongs to the import job.
                if (runCatching { pageStore.commitOrder(dir, s.pages.pages) }.isFailure) {
                    _state.update { it.copy(message = SAVE_FAILED) }
                    return null
                }
                if (s.kind == Sources.OTHER) {   // no book search, no author/ISBN; the remembered book stays
                    imports.enqueuePhotos(dir, s.title.trim().ifBlank { null }, Sources.resolveName(s.otherName, s.others),
                        s.note.trim().ifBlank { null }, sourceKind = Sources.OTHER)
                } else {
                    val book = Books.resolve(s.choice, s.books)
                    val title = book.title.ifBlank { null }
                    title?.let(rememberBook)   // the next scan's default; skipping it keeps the last one
                    imports.enqueuePhotos(dir, s.title.trim().ifBlank { null }, title, s.page.trim().ifBlank { null },
                        book.author, book.isbn)
                }
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
