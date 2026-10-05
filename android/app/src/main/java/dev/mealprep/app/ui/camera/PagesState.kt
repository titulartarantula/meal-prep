package dev.mealprep.app.ui.camera

import java.io.File

/** Pages of one recipe, in reading order (the server takes 1–10 and reads them in order as one recipe). */
data class PagesState(val pages: List<File> = emptyList(), val retaking: Int? = null) {
    companion object { const val MAX_PAGES = 10 }

    /** Another photo may be taken: there is room, or it replaces the page being retaken. */
    val canShoot: Boolean get() = retaking != null || pages.size < MAX_PAGES
    /** More pages than the server accepts (only possible from a gallery share): some must be deleted first. */
    val tooMany: Boolean get() = pages.size > MAX_PAGES

    fun add(f: File): PagesState = retaking?.let { i -> copy(pages = pages.toMutableList().also { it[i] = f }, retaking = null) }
        ?: copy(pages = pages + f)
    fun retake(i: Int) = copy(retaking = i.takeIf { it in pages.indices })
    fun cancelRetake() = copy(retaking = null)
    fun remove(i: Int) = copy(pages = pages.filterIndexed { j, _ -> j != i }, retaking = null)
    /** Swap page [i] with its neighbour [by] places away (−1 earlier, +1 later); unchanged past either end. */
    fun move(i: Int, by: Int): PagesState {
        val j = i + by
        if (i !in pages.indices || j !in pages.indices) return this
        return copy(pages = pages.toMutableList().also { it[i] = pages[j]; it[j] = pages[i] }, retaking = null)
    }
}
