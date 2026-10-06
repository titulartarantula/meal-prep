package dev.mealprep.app.core

import dev.mealprep.app.data.api.BookHit
import dev.mealprep.app.data.api.RecipeSource
import java.text.Normalizer

/**
 * "Which book?" (0.4.3): what is typed or picked, and the suggestions under the field. The household's own books
 * come first ("Your books", from GET /recipes/sources), then what the server's book search found (GET /books/search;
 * the server asks Open Library / Google Books, the phone never does). Free text is always fine.
 */
data class BookChoice(val title: String = "", val author: String? = null, val isbn: String? = null,
                      /** A suggestion (or the recipe's saved book with its details) is in the field: no rows shown. */
                      val picked: Boolean = false) {
    /** The field's text changed: a picked book's author/ISBN stay only while the text still names that book. */
    fun typed(text: String): BookChoice = if (picked && Books.same(text, title)) copy(title = text) else BookChoice(text)

    companion object {
        /** A recipe's saved book: its author/ISBN are kept unless the title is changed. */
        fun saved(title: String?, author: String?, isbn: String?) =
            BookChoice(title.orEmpty(), author, isbn, picked = title != null && (author != null || isbn != null))
    }
}

/** One row under "Which book?". [mine] = one of the household's books. */
data class BookSuggestion(
    val title: String, val author: String? = null, val isbn: String? = null, val subtitle: String? = null,
    val year: Int? = null, val mine: Boolean = false,
) {
    val choice: BookChoice get() = BookChoice(title, author, isbn, picked = true)
    /** The row's main line: the title, with the subtitle when the search has one. */
    val display: String get() = listOfNotNull(title, subtitle).joinToString(": ")
    /** The row's second line, "Julia Child · 1961". */
    val secondary: String? get() = listOfNotNull(author, year?.toString()).joinToString(" · ").ifEmpty { null }
    /** The second line for TalkBack: "by Julia Child, 1961". */
    val spoken: String? get() = listOfNotNull(author?.let { "by $it" }, year?.toString()).joinToString(", ").ifEmpty { null }
}

data class BookSuggestions(val yours: List<BookSuggestion> = emptyList(), val found: List<BookSuggestion> = emptyList()) {
    val isEmpty: Boolean get() = yours.isEmpty() && found.isEmpty()
}

object Books {
    const val MIN_QUERY = 2
    const val DEBOUNCE_MS = 300L
    const val LIMIT = 8

    /** Same title, ignoring case, accents, punctuation and spacing ("Salt, Fat, Acid, Heat" = "salt fat acid heat"). */
    fun same(a: String?, b: String?): Boolean = norm(a) == norm(b)

    private fun norm(s: String?): String =
        Normalizer.normalize(s.orEmpty(), Normalizer.Form.NFKD).replace(Regex("""\p{M}+"""), "").lowercase()
            .replace("&", " and ").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

    /** What is sent to the server for [text] (trimmed, single spaces); too short = null (nothing to ask). */
    fun query(text: String): String? = text.trim().replace(Regex("""\s+"""), " ").takeIf { it.length >= MIN_QUERY }

    /** "Julia Child, Simone Beck" (at most three names; the server keeps ≤ 200 characters). */
    fun authors(names: List<String>): String? =
        names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(3).joinToString(", ").take(200).ifEmpty { null }

    /** For the one-line source: the author(s) as given when short, else the first one + "et al.". */
    fun shortAuthor(author: String?): String? {
        val a = author?.trim()?.ifEmpty { null } ?: return null
        return if (a.length <= 30 || ',' !in a) a else a.substringBefore(',').trim() + " et al."
    }

    fun yours(sources: List<RecipeSource>): List<BookSuggestion> =
        sources.filter { it.kind == Sources.BOOK && it.title != null }
            .map { BookSuggestion(it.title!!, it.author, it.isbn, mine = true) }

    fun found(hits: List<BookHit>): List<BookSuggestion> =
        hits.map { BookSuggestion(it.title, authors(it.authors), it.isbn, it.subtitle, it.year) }

    /**
     * The rows to show for [choice]: none while a picked book is in the field; else the household's books that
     * contain what is typed (A–Z, the typed text itself left out) and then the search's [found] books, minus the
     * household's own ones already listed with an author.
     */
    fun suggest(yours: List<BookSuggestion>, found: List<BookSuggestion>, choice: BookChoice,
                maxYours: Int = 5, maxFound: Int = 6): BookSuggestions {
        if (choice.picked) return BookSuggestions()
        val t = choice.title.trim().lowercase()
        val mine = yours.distinctBy { it.title.lowercase() }
            .filter { b -> b.title.lowercase() != t && (t.isEmpty() || t in b.title.lowercase()) }
            .sortedBy { it.title.lowercase() }.take(maxYours)
        val known = yours.filter { it.author != null }
        val others = if (query(choice.title) == null) emptyList()
            else found.filterNot { f -> known.any { same(it.title, f.title) } }.take(maxFound)
        return BookSuggestions(mine, others)
    }

    /** The book to save: a typed title that is one of the household's books (any case) takes that book's
     *  spelling, author and ISBN. */
    fun resolve(choice: BookChoice, yours: List<BookSuggestion>): BookChoice {
        val title = choice.title.trim().replace(Regex("""\s+"""), " ")
        if (title.isEmpty()) return BookChoice()
        if (choice.author != null || choice.isbn != null) return BookChoice(title, choice.author, choice.isbn)
        val mine = yours.firstOrNull { it.title.equals(title, ignoreCase = true) && (it.author != null || it.isbn != null) }
        return mine?.let { BookChoice(it.title, it.author, it.isbn) } ?: BookChoice(title)
    }
}
