package dev.mealprep.app.core

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeeksTest {
    private val sun = LocalDate.parse("2026-10-04")
    private val wed = LocalDate.parse("2026-10-07")
    private val sat = LocalDate.parse("2026-10-10")

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
