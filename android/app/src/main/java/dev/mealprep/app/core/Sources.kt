package dev.mealprep.app.core

import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.RecipeSource

/**
 * Where a recipe comes from (server columns source_kind / source_title / source_ref, since 0.4.2), one of three kinds
 * (0.20.0): a website (title = the site's name, NYT Cooking included; author = the recipe's author; url = the page),
 * a cookbook (title null = not known yet, "Unknown book"; ref = page(s) as typed) or something else (0.7.1: title = a
 * name like "Mum's recipes", ref = an optional note shown after it; no name = just "Other").
 */
object Sources {
    const val WEB = "web"; const val BOOK = "book"; const val OTHER = "other"
    /** The site name of NYT Cooking recipes (the server stored them as kind "nyt" before 0.20.0). */
    const val NYT_SITE = "NYT Cooking"
    /** The three kinds as the library filter names them. */
    val KIND_LABELS = linkedMapOf(WEB to "Website", BOOK to "Book", OTHER to "Other")
    /** The library filter's "everything" (not a server key). */
    const val ALL = ""
    const val UNKNOWN_BOOK = "Unknown book"

    /** Website, Book or Other, also for a saved copy from an older server: "nyt" (or, before 0.4.2, no kind and an
     *  NYT link) = a website; a photo = a book. */
    fun kind(r: Recipe): String = when (val k = r.sourceKind.orEmpty()) {
        WEB, BOOK, OTHER -> k
        "nyt" -> WEB
        else -> when {
            isNyt(r.sourceUrl) || r.source == "web" -> WEB
            r.source == "photo" -> BOOK
            else -> OTHER
        }
    }

    private fun isNyt(url: String?) = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
        ?.let { it == "nytimes.com" || it.endsWith(".nytimes.com") } == true

    /** A website recipe's site name as saved (an NYT link without one: NYT Cooking), or null. */
    private fun siteName(r: Recipe): String? = r.sourceTitle?.let(::clean)?.ifEmpty { null }
        ?: if (kind(r) == WEB && isNyt(r.sourceUrl)) NYT_SITE else null

    /** A website recipe's site: its saved name, else the link's domain without "www.". */
    fun site(r: Recipe): String = siteName(r)
        ?: r.sourceUrl?.let { runCatching { java.net.URI(it).host?.removePrefix("www.") }.getOrNull() }?.ifEmpty { null }
        ?: "A website"

    /** "From Serious Eats · by J. Cook" (the author when the page names one). */
    fun attribution(r: Recipe): String =
        "From " + listOfNotNull(site(r), r.sourceAuthor?.let(::clean)?.ifEmpty { null }?.let { "by $it" }).joinToString(" · ")

    /** "p. 123", "pp. 12–13"; anything else as typed. */
    fun page(ref: String?): String? {
        val t = ref?.trim()?.ifEmpty { null } ?: return null
        return when {
            Regex("""\d+""").matches(t) -> "p. $t"
            Regex("""\d+\s*[-–,&]\s*\d+.*""").matches(t) -> "pp. " + t.replace(Regex("""\s*[-–]\s*"""), "–")
            else -> t
        }
    }

    /** "NYT Cooking · by J. Cook" (a website), "Salt Fat Acid Heat · Samin Nosrat, p. 123" (the author when known),
     *  "Unknown book", "Unknown book, p. 12", "Mum's recipes, the blue binder" (the note as typed), "Other". */
    fun label(r: Recipe): String = when (kind(r)) {
        BOOK -> listOfNotNull(r.sourceTitle?.let { t -> listOfNotNull(t, Books.shortAuthor(r.sourceAuthor)).joinToString(" · ") }
            ?: UNKNOWN_BOOK, page(r.sourceRef)).joinToString(", ")
        WEB -> listOfNotNull(site(r), r.sourceAuthor?.let(::clean)?.ifEmpty { null }?.let { "by $it" }).joinToString(" · ")
        else -> listOfNotNull(r.sourceTitle ?: "Other", r.sourceRef?.trim()?.ifEmpty { null }).joinToString(", ")
    }

    /** The source's own key, same as the server's GET /recipes?source= value: web:<site> / book:<title> /
     *  other:<name> (any case), web: (no site name), book: (unknown book), other: (no name). */
    fun key(r: Recipe): String = when (kind(r)) {
        BOOK -> "book:" + (r.sourceTitle?.let(::fold) ?: "")
        WEB -> "web:" + (siteName(r)?.let(::fold) ?: "")
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

    /** Does [r] pass the library filter [filter]: ALL, a kind (web / book / other) or one source's [key]? */
    fun matches(r: Recipe, filter: String): Boolean = filter == ALL || filter == kind(r) || filter == key(r)

    /** Filter choices for the recipes present: All sources; Website, Book, Other (always all three); then each site,
     *  book and named other source A–Z, and Unknown book; with counts. */
    fun options(all: List<Recipe>): List<Option> {
        val byKey = all.groupBy(::key)
        val kinds = KIND_LABELS.map { (k, label) -> Option(k, label, all.count { kind(it) == k }) }
        val named = byKey.keys.filter { it.substringAfter(":").isNotEmpty() }
            .map { k -> byKey.getValue(k).first().let { r -> Option(k, if (kind(r) == WEB) site(r) else clean(r.sourceTitle!!), byKey.getValue(k).size) } }
            .sortedWith(compareBy({ it.label.lowercase() }, { it.key }))
        return listOf(Option(ALL, "All sources", all.size)) + kinds + named +
            listOfNotNull(byKey["book:"]?.let { Option("book:", UNKNOWN_BOOK, it.size) })
    }
}
