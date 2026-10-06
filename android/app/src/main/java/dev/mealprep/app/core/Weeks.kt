package dev.mealprep.app.core

import dev.mealprep.app.data.api.WeekSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Weeks start on Sunday; day 0 = Sun … 6 = Sat (same as the server). */
object Weeks {
    private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val LONG = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    /** Planning horizon (0.4.3): the app plans at most 4 weeks in all, this week and the next 3. */
    const val HORIZON = 4
    /** Weeks the This-week pager reaches before this one. */
    const val PAST = 4
    /** How far later weeks are looked at for recipes already planned there (the server allows ≤ 52). */
    const val LOOK_AHEAD = 48

    fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())

    /** The weeks a picker offers: this week and the next 3. */
    fun horizon(today: LocalDate): List<LocalDate> = (0 until HORIZON).map { weekStart(today).plusWeeks(it.toLong()) }

    /** The first week after the horizon. */
    fun afterHorizon(today: LocalDate): LocalDate = weekStart(today).plusWeeks(HORIZON.toLong())

    /**
     * How many weeks after this one the This-week pager reaches: the next 3, further only so a later week that
     * already has recipes ([later]: summaries of the weeks after the horizon; planned before the limit, or by an
     * older app) or the week a link opens ([open]) is never hidden.
     */
    fun weeksAhead(today: LocalDate, later: List<WeekSummary>?, open: LocalDate? = null): Int {
        val planned = later.orEmpty().filter { it.entries > 0 }
            .mapNotNull { runCatching { weeksBetween(today, LocalDate.parse(it.week)) }.getOrNull() }
        return (planned + listOfNotNull(open?.let { weeksBetween(today, it) }) + (HORIZON - 1)).max().coerceAtMost(LOOK_AHEAD + HORIZON)
    }

    /** Default week for a new recipe: today if Sunday, else the coming Sunday (server api.default_week). */
    fun upcomingSunday(today: LocalDate): LocalDate =
        if (today.dayOfWeek == DayOfWeek.SUNDAY) today else weekStart(today).plusWeeks(1)

    fun nightDate(week: LocalDate, day: Int): LocalDate {
        require(day in 0..6) { "day must be 0 (Sun) … 6 (Sat)" }
        return weekStart(week).plusDays(day.toLong())
    }

    fun dayOf(date: LocalDate): Int = date.dayOfWeek.value % 7
    fun dayLabel(day: Int): String = DAYS[day]
    fun longDayLabel(day: Int): String = LONG[day]
    fun shortDate(d: LocalDate): String = d.format(SHORT_DATE)
    fun nightTitle(week: LocalDate, day: Int): String = "${DAYS[day]} ${nightDate(week, day).dayOfMonth}"

    fun weeksBetween(from: LocalDate, to: LocalDate): Int =
        ChronoUnit.WEEKS.between(weekStart(from), weekStart(to)).toInt()

    fun weekTitle(week: LocalDate, today: LocalDate): String = when (weeksBetween(today, week)) {
        0 -> "This week"
        1 -> "Next week"
        -1 -> "Last week"
        else -> "Week of ${shortDate(weekStart(week))}"
    }

    /** Week-picker label: "This week (Oct 4)", "Next week (Oct 11)", "Week of Oct 18". */
    fun weekChoiceLabel(week: LocalDate, today: LocalDate): String = when (weeksBetween(today, week)) {
        0, 1 -> "${weekTitle(week, today)} (${shortDate(weekStart(week))})"
        else -> weekTitle(week, today)
    }
}
