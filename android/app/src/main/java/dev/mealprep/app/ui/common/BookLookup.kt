package dev.mealprep.app.ui.common

import dev.mealprep.app.core.BookSuggestion
import dev.mealprep.app.core.Books
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.BookHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Asks the server's book search as "Which book?" is typed: [DEBOUNCE_MS][Books.DEBOUNCE_MS] after the last
 * keystroke, from 2 characters, once per query. [found] keeps the last answer until a new one arrives (no flicker
 * while typing); offline or an error is quietly no extra rows, so the household's own books remain.
 */
class BookLookup(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> ApiResult<List<BookHit>>,
    private val debounceMs: Long = Books.DEBOUNCE_MS,
) {
    private val _found = MutableStateFlow<List<BookSuggestion>>(emptyList())
    val found: StateFlow<List<BookSuggestion>> = _found.asStateFlow()
    private var job: Job? = null
    private var asked: String? = null

    fun typed(text: String) {
        val q = Books.query(text)
        if (q != null && q.equals(asked, ignoreCase = true)) return   // same question (asked, or waiting to be)
        job?.cancel()
        asked = q
        if (q == null) { _found.value = emptyList(); return }
        job = scope.launch {
            delay(debounceMs)
            _found.value = (search(q) as? ApiResult.Ok)?.value?.let(Books::found).orEmpty()
        }
    }
}
