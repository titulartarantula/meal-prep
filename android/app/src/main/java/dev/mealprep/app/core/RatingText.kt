package dev.mealprep.app.core

import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.data.api.Recipe
import java.util.Locale

object RatingText {
    /** "Family 4.5/5 · Company: yes · “less salt”" — shown on re-share, in the library and on the rating screen. */
    fun summary(r: RatingSummary): String? {
        if (r.timesRated == 0) return null
        return listOfNotNull(
            r.avgFamily?.let { "Family ${fmt(it)}/5" },
            r.company?.let { "Company: $it" },
            r.notes.firstOrNull()?.note?.let { "“$it”" },
        ).joinToString(" · ")
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(Locale.US, "%.1f", d)
}

/** First ingredient line that points at a cookbook page we don't have yet: (line index, page), or null. */
fun Recipe.firstMissingRef(): Pair<Int, Int>? =
    missingPages.firstNotNullOfOrNull { i -> ingredients.getOrNull(i)?.refPage?.let { i to it } }
