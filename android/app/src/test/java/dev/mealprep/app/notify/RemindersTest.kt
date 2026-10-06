package dev.mealprep.app.notify

import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.data.api.Rating
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.fixture
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val wk = LocalDate.parse("2026-10-11")
    private val sunMorning = LocalDateTime.parse("2026-10-11T10:00")
    private val prep = Http.json.decodeFromString(PrepPlan.serializer(), fixture("prep_plan_ready.json"))
    private val chili = PlanEntry(21, "2026-10-11", 5, 2, title = "Chili")
    private val soup = PlanEntry(22, "2026-10-11", 6, 4, title = "Fish soup")
    private fun plan(entries: List<PlanEntry>, p: PrepPlan? = prep, prefs: NotifPrefs = NotifPrefs(), now: LocalDateTime = sunMorning) =
        Reminders.plan(listOf(WeekData(wk, entries, p)), prefs, now).associateBy { it.id }

    @Test fun `morning after each placed, unrated dinner`() {
        val r = plan(listOf(chili, soup.copy(rating = Rating(4)), PlanEntry(23, "2026-10-11", 1, null, title = "Cookies")))
        assertEquals(LocalDateTime.parse("2026-10-14T09:00"), r["rate-21"]!!.at)
        assertEquals("How was Tuesday's Chili?", r["rate-21"]!!.title)
        assertEquals("rating/21/2026-10-11", r["rate-21"]!!.nav)
        assertEquals(21, r["rate-21"]!!.entryId); assertEquals(wk, r["rate-21"]!!.week)
        assertTrue("rate-22" !in r && "rate-23" !in r)
    }

    @Test fun `thaw the evening before, from the prep plan's freeze tasks`() {
        val t = plan(listOf(chili, soup))["thaw-22"]!!
        assertEquals(LocalDateTime.parse("2026-10-14T20:00"), t.at)
        assertEquals("Thaw for tomorrow's Fish soup", t.title)
        assertEquals("Freeze Sunday; move it to the fridge Wednesday night.", t.text)
        assertEquals("card/22", t.nav)
        assertTrue("thaw-21" !in plan(listOf(chili, soup)))                                 // chili's kit isn't frozen
    }

    @Test fun `thaw follows a dinner that moved, and disappears if it was removed`() {
        assertEquals(LocalDateTime.parse("2026-10-15T20:00"), plan(listOf(chili, soup.copy(day = 5)))["thaw-22"]!!.at)
        assertTrue("thaw-22" !in plan(listOf(chili)))
        assertTrue("thaw-22" !in plan(listOf(chili, soup.copy(day = null))))
        assertTrue("thaw-22" !in plan(listOf(chili, soup), p = prep.copy(status = "building")))
    }

    @Test fun `tonight only when switched on, custom times, nothing in the past`() {
        assertTrue(plan(listOf(chili)).keys.none { it.startsWith("tonight") })
        val prefs = NotifPrefs(tonight = true, tonightAt = LocalTime.of(16, 30), rateAt = LocalTime.of(8, 15), thaw = false)
        val r = plan(listOf(chili, soup), prefs = prefs)
        assertEquals(LocalDateTime.parse("2026-10-13T16:30"), r["tonight-21"]!!.at)
        assertEquals("Tonight: Chili", r["tonight-21"]!!.title)
        assertEquals(LocalDateTime.parse("2026-10-14T08:15"), r["rate-21"]!!.at)
        assertTrue("thaw-22" !in r)
        assertTrue(plan(listOf(chili), now = LocalDateTime.parse("2026-10-14T09:30")).isEmpty())
    }

    @Test fun `tonight opens the cook card, or the recipe when there is no card`() {
        val on = NotifPrefs(tonight = true)
        assertEquals("card/21", plan(listOf(chili), prefs = on)["tonight-21"]!!.nav)
        val r = plan(listOf(chili), p = null, prefs = on)["tonight-21"]!!
        assertEquals("recipe/5", r.nav); assertEquals("Tap for the recipe.", r.text)
    }

    @Test fun `every kind can be switched off`() {
        assertTrue(plan(listOf(chili, soup), prefs = NotifPrefs(thaw = false, rate = false, tonight = false)).isEmpty())
    }

    @Test fun `Saturday dinner is rated Sunday morning (next week)`() {
        val sat = PlanEntry(30, "2026-10-04", 7, 6, title = "Pizza")
        val r = Reminders.plan(listOf(WeekData(LocalDate.parse("2026-10-04"), listOf(sat), null)), NotifPrefs(), LocalDateTime.parse("2026-10-10T12:00"))
        assertEquals(LocalDateTime.parse("2026-10-11T09:00"), r.single().at)
    }

    @Test fun `one prep plan for two weeks gives one thaw per dinner, sorted by time`() {
        val next = WeekData(LocalDate.parse("2026-10-18"), emptyList(), prep)       // the same plan, read for the next week
        val r = Reminders.plan(listOf(WeekData(wk, listOf(chili, soup), prep), next), NotifPrefs(), sunMorning)
        assertEquals(1, r.count { it.id == "thaw-22" })
        assertEquals(r.sortedBy { it.at }, r)
    }
}
