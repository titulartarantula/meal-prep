package dev.mealprep.app.core

import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.RecipeSource

/**
 * Where a recipe comes from (server columns source_kind / source_title / source_ref, since 0.4.2): NYT Cooking, a
 * cookbook (title null = not known yet, "Unknown book"; ref = page(s) as typed) or something else (0.7.1: title = a
 * name like "Mum's recipes", ref = an optional note shown after it; no name = just "Other").
 */
object Sources {
    const val NYT = "nyt"; const val BOOK = "book"; const val OTHER = "other"
    /** The library filter's "everything" (not a server key). */
    const val ALL = ""
    const val UNKNOWN_BOOK = "Unknown book"

    /** The kind, also for a saved copy from before 0.4.2 (no source_kind): an NYT link, or a photo = a book. */
    fun kind(r: Recipe): String = r.sourceKind ?: when {
        r.sourceUrl?.contains("nytimes.com") == true -> NYT
        r.source == "photo" -> BOOK
        else -> OTHER
    }

    /** "p. 123", "pp. 12–13"; anything else as typed. */
    fun page(ref: String?): String? {
        val t = ref?.trim()?.ifEmpty { null } ?: return null
        return when {
            Regex("""\d+""").matches(t) -> "p. $t"
            Regex("""\d+\s*[-–,&]\s*\d+.*""").matches(t) -> "pp. " + t.replace(Regex("""\s*[-–]\s*"""), "–")
            else -> t
        }
    }

    /** "NYT Cooking", "Salt Fat Acid Heat · Samin Nosrat, p. 123" (the author when known), "Unknown book",
     *  "Unknown book, p. 12", "Mum's recipes, the blue binder" (the note as typed), "Other". */
    fun label(r: Recipe): String = when (kind(r)) {
        NYT -> "NYT Cooking"
        BOOK -> listOfNotNull(r.sourceTitle?.let { t -> listOfNotNull(t, Books.shortAuthor(r.sourceAuthor)).joinToString(" · ") }
            ?: UNKNOWN_BOOK, page(r.sourceRef)).joinToString(", ")
        else -> listOfNotNull(r.sourceTitle ?: "Other", r.sourceRef?.trim()?.ifEmpty { null }).joinToString(", ")
    }

    /** The filter key, same as the server's GET /recipes?source= value: nyt, book:<title> / other:<name> (any case),
     *  book: (unknown book), other: (no name). */
    fun key(r: Recipe): String = when (kind(r)) {
        NYT -> NYT
        BOOK -> "book:" + (r.sourceTitle?.let(::fold) ?: "")
        else -> "other:" + (r.sourceTitle?.let(::fold) ?: "")
    }

    private fun fold(t: String) = clean(t).lowercase()

    /** Trimmed, single spaces. */
    fun clean(t: String): String = t.trim().replace(Regex("""\s+"""), " ")

    /** The household's named other sources ("Mum's recipes"), A–Z, from GET /recipes/sources (books never). */
    fun otherNames(sources: List<RecipeSource>): List<String> =
        sources.filter { it.kind == OTHER && it.title != null }.map { clean(it.title!!) }
            .distinctBy { it.lowercase() }.sortedBy { it.lowercase() }

    /** The names to offer under the Name field: those containing what is typed (all when empty), the typed one left out. */
    fun suggestNames(names: List<String>, typed: String, max: Int = 8): List<String> {
        val t = clean(typed).lowercase()
        return names.filter { n -> n.lowercase() != t && t in n.lowercase() }.take(max)
    }

    /** The name to save: a household name typed in another case takes that spelling; blank = null (just "Other"). */
    fun resolveName(name: String, names: List<String>): String? {
        val n = clean(name).ifEmpty { return null }
        return names.firstOrNull { it.equals(n, ignoreCase = true) } ?: n
    }

    data class Option(val key: String, val label: String, val count: Int)

    /** Filter choices for the recipes present: All, NYT Cooking, each book and named other source A–Z, Unknown book,
     *  Other (no name), with counts. */
    fun options(all: List<Recipe>): List<Option> {
        val byKey = all.groupBy(::key)
        fun opt(k: String, label: String) = byKey[k]?.let { Option(k, label, it.size) }
        val named = byKey.keys.filter { (it.startsWith("book:") && it != "book:") || (it.startsWith("other:") && it != "other:") }
            .map { k -> Option(k, clean(byKey.getValue(k).first().sourceTitle!!), byKey.getValue(k).size) }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.key }))
        return listOfNotNull(Option(ALL, "All sources", all.size), opt(NYT, "NYT Cooking")) + named +
            listOfNotNull(opt("book:", UNKNOWN_BOOK), opt("other:", "Other"))
    }
}
