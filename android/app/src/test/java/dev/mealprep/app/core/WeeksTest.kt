package dev.mealprep.app.core

import dev.mealprep.app.data.api.WeekSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeksTest {
    private val sun = LocalDate.parse("2026-10-04")
    private val wed = LocalDate.parse("2026-10-07")
    private val sat = LocalDate.parse("2026-10-10")

    @Test fun `the planning horizon is this week and the next three`() {
        val four = listOf("2026-10-04", "2026-10-11", "2026-10-18", "2026-10-25").map(LocalDate::parse)
        assertEquals(four, Weeks.horizon(wed))
        assertEquals(four, Weeks.horizon(sun))
        assertEquals(four, Weeks.horizon(sat))
        assertEquals(LocalDate.parse("2026-11-01"), Weeks.afterHorizon(sat))
    }

    @Test fun `the pager reaches further only for weeks that already have recipes or are opened`() {
        assertEquals(3, Weeks.weeksAhead(wed, null))                                    // offline, nothing saved
        assertEquals(3, Weeks.weeksAhead(wed, listOf(WeekSummary("2026-11-01", 0), WeekSummary("2026-11-08", 0))))
        assertEquals(5, Weeks.weeksAhead(wed, listOf(WeekSummary("2026-11-01", 0), WeekSummary("2026-11-08", 2),
            WeekSummary("2026-11-15", 0))))                                              // Nov 8 is 5 weeks after Oct 4
        assertEquals(7, Weeks.weeksAhead(wed, emptyList(), open = LocalDate.parse("2026-11-22")))
        assertEquals(3, Weeks.weeksAhead(wed, emptyList(), open = LocalDate.parse("2026-09-20")))
        assertEquals(3, Weeks.weeksAhead(wed, listOf(WeekSummary("not a date", 3))))
    }

    @Test fun `week starts on the Sunday on or before`() {
        assertEquals(sun, Weeks.weekStart(sun))
        assertEquals(sun, Weeks.weekStart(wed))
        assertEquals(sun, Weeks.weekStart(sat))
    }

    @Test fun `share default is today on Sunday else the coming Sunday (server default_week)`() {
        assertEquals(sun, Weeks.upcomingSunday(sun))
        assertEquals(LocalDate.parse("2026-10-11"), Weeks.upcomingSunday(LocalDate.parse("2026-10-05")))
        assertEquals(LocalDate.parse("2026-10-11"), Weeks.upcomingSunday(sat))
    }

    @Test fun `night dates and labels use 0 = Sunday`() {
        val week = LocalDate.parse("2026-10-11")
        assertEquals(LocalDate.parse("2026-10-13"), Weeks.nightDate(week, 2))
        assertEquals(2, Weeks.dayOf(LocalDate.parse("2026-10-13")))
        assertEquals(0, Weeks.dayOf(week))
        assertEquals("Tue", Weeks.dayLabel(2))
        assertEquals("Saturday", Weeks.longDayLabel(6))
        assertEquals("Tue 13", Weeks.nightTitle(week, 2))
    }

    @Test fun `a week's dates for its header`() {
        assertEquals("Oct 11 – 17", Weeks.range(LocalDate.parse("2026-10-14")))
        assertEquals("Sep 27 – Oct 3", Weeks.range(LocalDate.parse("2026-09-27")))
        assertEquals("Dec 27 – Jan 2", Weeks.range(LocalDate.parse("2026-12-27")))
        assertEquals("Sep 27 to Oct 3", Weeks.spokenRange(LocalDate.parse("2026-09-27")))
    }

    @Test fun `week titles relative to today`() {
        assertEquals("This week", Weeks.weekTitle(sun, wed))
        assertEquals("Next week", Weeks.weekTitle(LocalDate.parse("2026-10-11"), wed))
        assertEquals("Last week", Weeks.weekTitle(LocalDate.parse("2026-09-27"), wed))
        assertEquals("Week of Oct 18", Weeks.weekTitle(LocalDate.parse("2026-10-18"), wed))
        assertEquals("Next week (Oct 11)", Weeks.weekChoiceLabel(LocalDate.parse("2026-10-11"), wed))
        assertEquals("Week of Oct 25", Weeks.weekChoiceLabel(LocalDate.parse("2026-10-25"), wed))
        assertEquals(2, Weeks.weeksBetween(wed, LocalDate.parse("2026-10-21")))
    }
}
