package dev.mealprep.app.core

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

    fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value % 7).toLong())

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
