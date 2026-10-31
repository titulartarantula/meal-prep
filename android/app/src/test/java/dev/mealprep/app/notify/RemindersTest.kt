package dev.mealprep.app.notify

import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.Rating
import dev.mealprep.app.data.settings.NotifPrefs
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val wk = LocalDate.parse("2026-10-11")
    private val sunMorning = LocalDateTime.parse("2026-10-11T10:00")
    private val chili = PlanEntry(21, "2026-10-11", 5, 2, title = "Chili")
    private val soup = PlanEntry(22, "2026-10-11", 6, 4, title = "Fish soup")
    private fun plan(entries: List<PlanEntry>, prefs: NotifPrefs = NotifPrefs(), now: LocalDateTime = sunMorning) =
        Reminders.plan(listOf(WeekData(wk, entries)), prefs, now).associateBy { it.id }

    @Test fun `morning after each placed, unrated dinner`() {
        val r = plan(listOf(chili, soup.copy(rating = Rating(4)), PlanEntry(23, "2026-10-11", 1, null, title = "Cookies")))
        assertEquals(LocalDateTime.parse("2026-10-14T09:00"), r["rate-21"]!!.at)
        assertEquals("How was Tuesday's Chili?", r["rate-21"]!!.title)
        assertEquals("rating/21/2026-10-11", r["rate-21"]!!.nav)
        assertEquals(21, r["rate-21"]!!.entryId); assertEquals(wk, r["rate-21"]!!.week)
        assertTrue("rate-22" !in r && "rate-23" !in r)
    }

    @Test fun `only rating and tonight reminders are planned (no thaw since Sunday prep was retired)`() {
        val r = plan(listOf(chili, soup), prefs = NotifPrefs(tonight = true))
        assertEquals(setOf("rate-21", "rate-22", "tonight-21", "tonight-22"), r.keys)
        assertEquals(listOf("RATE", "TONIGHT"), ReminderKind.entries.map { it.name })
    }

    @Test fun `tonight only when switched on, custom times, nothing in the past`() {
        assertTrue(plan(listOf(chili)).keys.none { it.startsWith("tonight") })
        val prefs = NotifPrefs(tonight = true, tonightAt = LocalTime.of(16, 30), rateAt = LocalTime.of(8, 15))
        val r = plan(listOf(chili, soup), prefs = prefs)
        assertEquals(LocalDateTime.parse("2026-10-13T16:30"), r["tonight-21"]!!.at)
        assertEquals("Tonight: Chili", r["tonight-21"]!!.title)
        assertEquals(LocalDateTime.parse("2026-10-14T08:15"), r["rate-21"]!!.at)
        assertTrue(plan(listOf(chili), now = LocalDateTime.parse("2026-10-14T09:30")).isEmpty())
    }

    @Test fun `tonight opens the recipe`() {
        val r = plan(listOf(chili), prefs = NotifPrefs(tonight = true))["tonight-21"]!!
        assertEquals("recipe/5", r.nav); assertEquals("Tap for the recipe.", r.text)
    }

    @Test fun `every kind can be switched off`() {
        assertTrue(plan(listOf(chili, soup), prefs = NotifPrefs(rate = false, tonight = false)).isEmpty())
    }

    @Test fun `Saturday dinner is rated Sunday morning (next week)`() {
        val sat = PlanEntry(30, "2026-10-04", 7, 6, title = "Pizza")
        val r = Reminders.plan(listOf(WeekData(LocalDate.parse("2026-10-04"), listOf(sat))), NotifPrefs(), LocalDateTime.parse("2026-10-10T12:00"))
        assertEquals(LocalDateTime.parse("2026-10-11T09:00"), r.single().at)
    }

    @Test fun `two weeks, sorted by time`() {
        val next = WeekData(LocalDate.parse("2026-10-18"), listOf(PlanEntry(40, "2026-10-18", 8, 1, title = "Tacos")))
        val r = Reminders.plan(listOf(next, WeekData(wk, listOf(chili, soup))), NotifPrefs(), sunMorning)
        assertEquals(listOf("rate-21", "rate-22", "rate-40"), r.map { it.id })
    }
}
