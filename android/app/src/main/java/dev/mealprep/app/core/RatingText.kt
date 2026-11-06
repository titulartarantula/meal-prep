package dev.mealprep.app.core

import dev.mealprep.app.data.api.PersonRating
import dev.mealprep.app.data.api.PersonSummary
import dev.mealprep.app.data.api.RatingNote
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.data.api.Recipe
import java.util.Locale

object RatingText {
    /** "Average 4.5/5 from 6 ratings · “less salt”" — shown on re-share, in the library and on the rating screen.
     *  Good for company is the household's Yes / No, not a score, so it isn't part of this line ([company]). */
    fun summary(r: RatingSummary): String? {
        if (r.timesRated == 0) return null
        val n = r.ratingCount.takeIf { it > 0 } ?: r.timesRated
        return listOfNotNull(
            r.avgFamily?.let { "Average ${fmt(it)}/5 from $n ${if (n == 1) "rating" else "ratings"}" },
            r.notes.firstOrNull()?.note?.let { "“$it”" },
        ).joinToString(" · ")
    }

    /** "Yes" / "No"; null = not decided (an old "maybe" reads as not decided). */
    fun companyLabel(c: String?): String? = when (c) { "yes" -> "Yes"; "no" -> "No"; else -> null }

    /** "Good for company: Yes" (or "… not decided yet"), with who set it when known. */
    fun company(r: RatingSummary): String =
        companyLabel(r.company)?.let { "Good for company: $it" + (r.companySetByName?.let { n -> " (set by $n)" } ?: "") }
            ?: "Good for company: not decided yet"

    /** A person's name as this phone's person sees it: their own says "(you)". */
    fun person(name: String, memberId: Int?, me: Int?): String = if (me != null && memberId == me) "$name (you)" else name

    /** "Sam (you): 4/5 · “less salt”" (a child's: "· entered by Alex"). */
    fun personLine(r: PersonRating, me: Int?): String = listOfNotNull(
        "${person(r.name, r.memberId, me)}: ${r.family}/5", r.note?.let { "“$it”" }, r.enteredByName?.let { "entered by $it" },
    ).joinToString(" · ")

    /** "Sam (you): 4/5 last time · average 4.5 from 2". */
    fun personSummary(p: PersonSummary, me: Int?): String = "${person(p.name, p.memberId, me)}: ${p.family}/5" +
        if (p.count > 1) " last time · average ${fmt(p.avg)} from ${p.count}" else ""

    /** "“less salt” — Sam, Oct 6" (name and date when known). */
    fun note(n: RatingNote, me: Int?, date: (String) -> String): String {
        val tail = listOfNotNull(n.name?.let { person(it, n.memberId, me) }, n.date?.let(date)).joinToString(", ")
        return "“${n.note}”" + if (tail.isNotEmpty()) " — $tail" else ""
    }

    /** A time cooked: "rated 4/5", or "rated 4.5/5 by 2" when several people rated it. */
    fun cooked(avg: Double?, count: Int, own: Int?): String? = when {
        count > 1 && avg != null -> "rated ${fmt(avg)}/5 by $count"
        avg != null -> "rated ${fmt(avg)}/5"
        own != null -> "rated $own/5"
        else -> null
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
