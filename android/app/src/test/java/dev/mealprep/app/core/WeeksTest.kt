package dev.mealprep.app.core

import dev.mealprep.app.data.api.WeekSummary
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WeeksTest {
    private val mon = LocalDate.parse("2026-10-05")
    private val wed = LocalDate.parse("2026-10-07")
    private val sun = LocalDate.parse("2026-10-11")   // the last day of mon's week

    @Test fun `the planning horizon is this week and the next three`() {
        val four = listOf("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-26").map(LocalDate::parse)
        assertEquals(four, Weeks.horizon(wed))
        assertEquals(four, Weeks.horizon(mon))
        assertEquals(four, Weeks.horizon(sun))
        assertEquals(LocalDate.parse("2026-11-02"), Weeks.afterHorizon(sun))
    }

    @Test fun `the pager reaches further only for weeks that already have recipes or are opened`() {
        assertEquals(3, Weeks.weeksAhead(wed, null))                                    // offline, nothing saved
        assertEquals(3, Weeks.weeksAhead(wed, listOf(WeekSummary("2026-11-02", 0), WeekSummary("2026-11-09", 0))))
        assertEquals(5, Weeks.weeksAhead(wed, listOf(WeekSummary("2026-11-02", 0), WeekSummary("2026-11-09", 2),
            WeekSummary("2026-11-16", 0))))                                              // Nov 9 is 5 weeks after Oct 5
        assertEquals(7, Weeks.weeksAhead(wed, emptyList(), open = LocalDate.parse("2026-11-23")))
        assertEquals(3, Weeks.weeksAhead(wed, emptyList(), open = LocalDate.parse("2026-09-21")))
        assertEquals(3, Weeks.weeksAhead(wed, listOf(WeekSummary("not a date", 3))))
    }

    @Test fun `week starts on the Monday on or before`() {
        assertEquals(mon, Weeks.weekStart(mon))
        assertEquals(mon, Weeks.weekStart(wed))
        assertEquals(mon, Weeks.weekStart(sun))                                          // Sunday ends the week
        assertEquals(LocalDate.parse("2026-10-12"), Weeks.weekStart(sun.plusDays(1)))
        assertEquals(LocalDate.parse("2026-12-28"), Weeks.weekStart(LocalDate.parse("2027-01-03")))   // across a year
    }

    @Test fun `new recipe default is today on Monday else the coming Monday (server default_week)`() {
        assertEquals(mon, Weeks.upcomingMonday(mon))
        assertEquals(LocalDate.parse("2026-10-12"), Weeks.upcomingMonday(wed))
        assertEquals(LocalDate.parse("2026-10-12"), Weeks.upcomingMonday(sun))          // Sunday: the next day's week
        assertEquals(LocalDate.parse("2026-11-16"), Weeks.upcomingMonday(LocalDate.parse("2026-11-14")))
    }

    @Test fun `night dates and labels use 0 = Monday`() {
        val week = LocalDate.parse("2026-10-12")
        assertEquals(week, Weeks.nightDate(week, 0))
        assertEquals(LocalDate.parse("2026-10-14"), Weeks.nightDate(week, 2))
        assertEquals(LocalDate.parse("2026-10-18"), Weeks.nightDate(week, 6))
        assertEquals(LocalDate.parse("2026-10-18"), Weeks.nightDate(LocalDate.parse("2026-10-15"), 6))   // any day of it
        assertEquals(0, Weeks.dayOf(week))
        assertEquals(2, Weeks.dayOf(LocalDate.parse("2026-10-14")))
        assertEquals(6, Weeks.dayOf(LocalDate.parse("2026-10-18")))
        assertEquals(0, Weeks.dayOf(DayOfWeek.MONDAY)); assertEquals(6, Weeks.dayOf(DayOfWeek.SUNDAY))
        assertEquals("Mon", Weeks.dayLabel(0)); assertEquals("Wed", Weeks.dayLabel(2)); assertEquals("Sun", Weeks.dayLabel(6))
        assertEquals("Monday", Weeks.longDayLabel(0)); assertEquals("Sunday", Weeks.longDayLabel(6))
        assertEquals("Mon 12", Weeks.nightTitle(week, 0))
        assertEquals("Wed 14", Weeks.nightTitle(week, 2))
        assertEquals("Sun 18", Weeks.nightTitle(week, 6))
        assertThrows(IllegalArgumentException::class.java) { Weeks.nightDate(week, 7) }
    }

    @Test fun `a week's dates for its header`() {
        assertEquals("Oct 12 – 18", Weeks.range(LocalDate.parse("2026-10-14")))
        assertEquals("Oct 12 – 18", Weeks.range(LocalDate.parse("2026-10-18")))          // its Sunday
        assertEquals("Sep 28 – Oct 4", Weeks.range(LocalDate.parse("2026-09-28")))
        assertEquals("Dec 28 – Jan 3", Weeks.range(LocalDate.parse("2026-12-28")))
        assertEquals("Sep 28 to Oct 4", Weeks.spokenRange(LocalDate.parse("2026-09-28")))
    }

    @Test fun `week titles relative to today`() {
        assertEquals("This week", Weeks.weekTitle(mon, wed))
        assertEquals("This week", Weeks.weekTitle(mon, sun))
        assertEquals("Next week", Weeks.weekTitle(LocalDate.parse("2026-10-12"), wed))
        assertEquals("Next week", Weeks.weekTitle(LocalDate.parse("2026-10-12"), sun))   // Sunday: tomorrow's week is next
        assertEquals("Last week", Weeks.weekTitle(LocalDate.parse("2026-09-28"), wed))
        assertEquals("Week of Oct 19", Weeks.weekTitle(LocalDate.parse("2026-10-19"), wed))
        assertEquals("Next week (Oct 12)", Weeks.weekChoiceLabel(LocalDate.parse("2026-10-12"), wed))
        assertEquals("Week of Oct 26", Weeks.weekChoiceLabel(LocalDate.parse("2026-10-26"), wed))
        assertEquals(2, Weeks.weeksBetween(wed, LocalDate.parse("2026-10-21")))
        assertEquals(1, Weeks.weeksBetween(sun, LocalDate.parse("2026-10-12")))
    }
}
