package dev.mealprep.app.ui.fridge

import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.Pick
import dev.mealprep.app.data.api.Suggestion
import dev.mealprep.app.ui.home.andList
import java.time.LocalDate
import java.util.Locale

/** The dinner suggestions' texts, pure (SuggestLogicTest). */
object SuggestLogic {
    const val OFFLINE = "Suggestions need the home network (or WireGuard)."

    /** "Uses: 4 Eggs, 1 bag Spinach and Cilantro". */
    fun uses(p: Pick): String = "Uses: " + andList(p.uses.map { it.amount })

    /** "Still need: chicken thigh, lime and 2 more"; "Nothing else to buy" when the fridge and the pantry cover it. */
    fun stillNeed(p: Pick): String = when {
        p.stillNeed.isEmpty() -> "Nothing else to buy"
        p.stillNeedMore > 0 -> "Still need: ${p.stillNeed.joinToString(", ")} and ${p.stillNeedMore} more"
        else -> "Still need: " + andList(p.stillNeed)
    }

    /** "⚠ Eggs: last checked 9 days ago" for the stale items this pick uses. */
    fun staleLines(p: Pick, s: Suggestion): List<String> =
        s.stale.filter { t -> p.uses.any { it.stale && t.startsWith(it.name + ":") } }.map { "⚠ $it" }

    /** "★ 4.5" and what TalkBack says for it; null when never rated. */
    fun rating(p: Pick): Pair<String, String>? = p.ratings.avgFamily?.let { a ->
        val n = String.format(Locale.US, "%.1f", a).removeSuffix(".0")
        "★ $n" to "Rated $n out of 5"
    }

    fun thinText(s: Suggestion): String? = if (!s.thinLibrary) null else when (val n = s.picks.size) {
        0 -> "No recipe in Recipes uses what's in the fridge."
        1 -> "Only 1 recipe in Recipes uses what's in the fridge."
        else -> "Only $n recipes in Recipes use what's in the fridge."
    }

    /** "this week", "next week", "the week of Oct 25". */
    fun weekName(week: LocalDate, today: LocalDate): String {
        val cur = Weeks.weekStart(today)
        return when (Weeks.weekStart(week)) { cur -> "this week"; cur.plusWeeks(1) -> "next week"; else -> "the week of ${Weeks.shortDate(week)}" }
    }

    fun addLabel(n: Int, week: LocalDate, today: LocalDate): String = "Add $n to ${weekName(week, today)}"
    fun nights(n: Int) = "$n night${if (n == 1) "" else "s"}"
    fun people(n: Int) = "$n ${if (n == 1) "person" else "people"}"
}
