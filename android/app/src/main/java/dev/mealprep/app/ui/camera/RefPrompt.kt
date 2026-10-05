package dev.mealprep.app.ui.camera

import dev.mealprep.app.data.api.Recipe

/** A line like "Batter for 24 crêpes, page 191" whose page isn't attached yet: its ingredients are missing from
 *  the shopping list until a photo of that page is added (POST /recipes/{id}/pages with for_line = [line]). */
data class RefPrompt(val recipeId: Int, val line: Int, val raw: String, val page: Int)

/** The first such line, or null when every referenced page is attached (or there are none). */
fun refPrompt(recipe: Recipe): RefPrompt? = recipe.missingPages.firstNotNullOfOrNull { i ->
    val ing = recipe.ingredients.getOrNull(i) ?: return@firstNotNullOfOrNull null
    ing.refPage?.let { RefPrompt(recipe.id, i, ing.raw, it) }
}

fun refPromptText(p: RefPrompt) = refPromptText(p.raw, p.page)
fun refPromptText(raw: String, page: Int) = "This uses “$raw” — add a photo of page $page?"

/**
 * The line to attach a photo of [page] to, checked against the recipe as it is now: attaching a page inserts the
 * sub-recipe's ingredients, so a line number from an older card or notification may point elsewhere. [line] wins
 * if it still refers to [page] (or [page] is unknown, 0) and isn't attached; else the first un-attached line that
 * refers to [page]. Null = nothing left to attach (already added).
 */
fun resolveRefLine(recipe: Recipe, line: Int, page: Int): Int? {
    fun open(i: Int) = recipe.ingredients.getOrNull(i)?.let { it.refPage != null && !it.expanded && (page <= 0 || it.refPage == page) } == true
    if (open(line)) return line
    if (page <= 0) return null
    return recipe.ingredients.indices.firstOrNull(::open)
}
