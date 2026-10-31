package dev.mealprep.app.notify

import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.ui.nav.Nav
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.Serializable

enum class ReminderKind { RATE, TONIGHT }

/** One local notification at [at] (wall-clock time on this phone). [entryId]/[week]: the dinner it is about, so the
 *  worker can check it still applies when it's due. */
data class Reminder(
    val id: String, val kind: ReminderKind, val at: LocalDateTime, val title: String, val text: String, val nav: String,
    val entryId: Int = 0, val week: LocalDate? = null,
)

/** A week's plan entries, from the server or the saved copy. */
data class WeekData(val week: LocalDate, val entries: List<PlanEntry>)

/** What Settings lists under "Coming up on this phone" (saved by the last sync), and its samples use. */
@Serializable data class PlannedReminder(
    val id: String, val kind: String, val at: String, val title: String, val text: String = "", val nav: String? = null,
)

/**
 * Planned on each phone from its copy of the weeks (the server is LAN-only, so there is no push
 * service; both phones do this and both get the notifications):
 * - RATE: the morning after each placed dinner not rated yet ("How was Tuesday's Chili?").
 * - TONIGHT (off unless chosen): on the day, opening the recipe.
 * Only reminders after [now] are returned.
 */
object Reminders {
    fun plan(weeks: List<WeekData>, prefs: NotifPrefs, now: LocalDateTime): List<Reminder> {
        val out = mutableListOf<Reminder>()
        val placed = weeks.flatMap { w -> w.entries.filter { it.day != null }.map { it to Weeks.weekStart(w.week) } }
            .associateBy { it.first.id }
        for ((e, week) in placed.values) {
            val date = Weeks.nightDate(week, e.day!!)
            val title = e.title ?: "dinner"
            if (prefs.rate && e.rating == null) out += Reminder("rate-${e.id}", ReminderKind.RATE, date.plusDays(1).atTime(prefs.rateAt),
                "How was ${Weeks.longDayLabel(e.day)}'s $title?", "Tap to rate it: was it a hit, and would you make it for company?",
                Nav.rating(e.id, week), e.id, week)
            if (prefs.tonight) out += Reminder("tonight-${e.id}", ReminderKind.TONIGHT, date.atTime(prefs.tonightAt),
                "Tonight: $title", "Tap for the recipe.", Nav.recipe(e.recipeId), e.id, week)
        }
        return out.filter { it.at.isAfter(now) }.sortedBy { it.at }
    }

    /**
     * When a reminder is due: is it still wanted? Its week is planned again from the newest copy (the other phone
     * may have rated the dinner, moved or removed it since the last sync). Not known (no copy at all): show it.
     */
    suspend fun stillWanted(r: Reminder, repo: Repository, prefs: NotifPrefs): Boolean {
        val week = r.week ?: return true
        val entries = repo.week(week).value ?: return true
        return plan(listOf(WeekData(week, entries)), prefs, r.at.minusMinutes(1)).any { it.id == r.id && it.at == r.at }
    }
}
