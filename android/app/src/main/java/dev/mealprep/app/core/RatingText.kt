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

    /** For TalkBack: "Family 4.5 out of 5" (it reads "4.5/5" as "4.5 slash 5"). */
    fun spoken(text: String): String = text.replace(Regex("""(\d+(?:\.\d+)?)/5\b"""), "$1 out of 5")

    /** "incl. 2 from another library" when some ratings came with an imported recipe (they count like the household's
     *  own); null when none did. [spoken]: "including …" for TalkBack. */
    fun imported(r: RatingSummary, spoken: Boolean = false): String? =
        r.importedRatings.takeIf { it > 0 }?.let { "${if (spoken) "including" else "incl."} $it from another library" }

    /** 4.0 → "4", 4.5 → "4.5". */
    fun number(d: Double) = fmt(d)

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(Locale.US, "%.1f", d)
}

/** First ingredient line that points at a cookbook page we don't have yet: (line index, page), or null. */
fun Recipe.firstMissingRef(): Pair<Int, Int>? =
    missingPages.firstNotNullOfOrNull { i -> ingredients.getOrNull(i)?.refPage?.let { i to it } }
