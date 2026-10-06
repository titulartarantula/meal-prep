package dev.mealprep.app.core

import dev.mealprep.app.data.api.Recipe

/**
 * Where a recipe comes from (server columns source_kind / source_title / source_ref, since 0.4.2): NYT Cooking, a
 * cookbook (title null = not known yet, "Unknown book"; ref = page(s) as typed) or something else.
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
     *  "Unknown book, p. 12", "Other". */
    fun label(r: Recipe): String = when (kind(r)) {
        NYT -> "NYT Cooking"
        BOOK -> listOfNotNull(r.sourceTitle?.let { t -> listOfNotNull(t, Books.shortAuthor(r.sourceAuthor)).joinToString(" · ") }
            ?: UNKNOWN_BOOK, page(r.sourceRef)).joinToString(", ")
        else -> listOfNotNull(r.sourceTitle ?: "Other", page(r.sourceRef)).joinToString(", ")
    }

    /** The filter key, same as the server's GET /recipes?source= value: nyt, book:<title> (any case), book: (unknown), other. */
    fun key(r: Recipe): String = when (kind(r)) {
        NYT -> NYT
        BOOK -> "book:" + (r.sourceTitle?.let(::fold) ?: "")
        else -> OTHER
    }

    private fun fold(t: String) = t.trim().replace(Regex("""\s+"""), " ").lowercase()

    data class Option(val key: String, val label: String, val count: Int)

    /** Filter choices for the recipes present: All, NYT Cooking, each book A–Z, Unknown book, Other (with counts). */
    fun options(all: List<Recipe>): List<Option> {
        val byKey = all.groupBy(::key)
        fun opt(k: String, label: String) = byKey[k]?.let { Option(k, label, it.size) }
        val books = byKey.keys.filter { it.startsWith("book:") && it != "book:" }
            .map { k -> Option(k, byKey.getValue(k).first().sourceTitle!!.trim(), byKey.getValue(k).size) }
            .sortedBy { it.label.lowercase() }
        return listOfNotNull(Option(ALL, "All sources", all.size), opt(NYT, "NYT Cooking")) + books +
            listOfNotNull(opt("book:", UNKNOWN_BOOK), opt(OTHER, "Other"))
    }
}
