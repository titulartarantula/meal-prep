package dev.mealprep.app.ui.prep

import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.data.api.PrepTask
import dev.mealprep.app.fixture
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrepScreenTest {
    @get:Rule val compose = createComposeRule()
    private val list get() = compose.onNode(hasScrollAction())
    private fun scrollTo(text: String) = list.performScrollToNode(hasText(text)).let { compose.onNodeWithText(text) }
    private val wk = LocalDate.parse("2026-10-11")
    private val today = LocalDate.parse("2026-10-10")
    private val ready = Http.json.decodeFromString(PrepPlan.serializer(), fixture("prep_plan_ready.json"))
    private val building = Http.json.decodeFromString(PrepPlan.serializer(), fixture("prep_plan_building.json"))

    @Test fun `no plan yet offers to write one`() {
        var started = 0
        compose.setContent { PrepContent(PrepState(loading = false), wk, today, PrepActions(onStart = { started++ })) }
        compose.onNodeWithText("Next week (Oct 11)").assertExists()
        compose.onNodeWithText(NO_PLAN).assertExists()
        compose.onNodeWithText("Write my prep plan").performClick()
        assertEquals(1, started)
    }

    @Test fun `ready plan shows the count, a row tap ticks, day-of rows do not, cook cards open`() {
        val ticked = mutableListOf<PrepTask>(); var card: Int? = null
        compose.setContent { PrepContent(PrepState(plan = ready, loading = false), wk, today,
            PrepActions(onToggle = { ticked += it }, onCard = { card = it })) }
        compose.onNodeWithText("1 of 4 done · about 27 min").assertExists()
        compose.onNodeWithText("⚠ Thu fish soup: cod frozen Sunday").assertExists()
        scrollTo("TUE – chili kit").performClick()
        scrollTo("Chop the cilantro").performClick()
        assertEquals(listOf("pack-1"), ticked.map { it.id })
        scrollTo("Thaw: Freeze Sunday; move it to the fridge Wednesday night.").assertExists()
        scrollTo("• spice blend jar").assertExists()
        scrollTo("Tue: Chili").performClick()
        assertEquals(21, card)
    }

    @Test fun `building plan shows progress and the last plan below`() {
        compose.setContent { PrepContent(PrepState(plan = building, previous = ready, loading = false), wk, today, PrepActions()) }
        compose.onNodeWithText(WRITING).assertExists()
        compose.onNodeWithText("Writing the prep plan…").assertExists()
        compose.onNodeWithText("Below: the last plan, until the new one is ready.").assertExists()
        compose.onNodeWithText("1 of 4 done · about 27 min").assertExists()
    }

    @Test fun `failed plan says why and offers Try again`() {
        var started = 0
        val failed = PrepPlan(6, "failed", error = "AI timed out", lastReadyId = null)
        compose.setContent { PrepContent(PrepState(plan = failed, loading = false), wk, today, PrepActions(onStart = { started++ })) }
        compose.onNodeWithText("The prep plan couldn't be written: AI timed out").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, started)
    }

    @Test fun `a changed week asks before a new plan throws ticks away`() {
        var started = 0
        compose.setContent { PrepContent(PrepState(plan = ready.copy(stale = true), loading = false), wk, today,
            PrepActions(onStart = { started++ })) }
        compose.onNodeWithText("Write a new plan").performClick()
        compose.onNodeWithText("Keep this one").performClick()
        assertEquals(0, started)
        compose.onNodeWithText("Write a new plan").performClick()
        compose.onNodeWithText("Write it").performClick()
        assertEquals(1, started)
    }

    @Test fun `all done shows how long it took`() {
        val all = ready.copy(checklist = ready.checklist.copy(done = 4, actualMinutes = 31))
        assertEquals("All 4 done · took 31 min", checklistText(all))
        compose.setContent { PrepContent(PrepState(plan = all, loading = false), wk, today, PrepActions()) }
        compose.onNodeWithText("All 4 done · took 31 min").assertExists()
    }

    @Test fun `task details name the nights, minutes and the safety rule`() {
        val t = ready.sections[2].tasks[0]
        assertEquals("Thu Fish soup · about 7 min · freeze", taskDetails(t))
        assertEquals("Tue Chili · about 3 min", taskDetails(ready.sections[0].tasks[1]))
        compose.setContent { PrepContent(PrepState(plan = ready, loading = false), wk, today, PrepActions()) }
        scrollTo("Portion the cod and freeze").assertIsOff()
        // A task for the night says so instead of showing a disabled checkbox.
        scrollTo("Chop the cilantro")
        compose.onAllNodesWithText(ON_THE_NIGHT).onFirst().assertExists()
        compose.onAllNodes(isToggleable()).assertAll(isEnabled())
    }

    @Test fun `prep warnings are advice, not errors`() {
        compose.setContent { PrepContent(PrepState(plan = ready, loading = false), wk, today, PrepActions()) }
        compose.onNodeWithText("⚠ Thu fish soup: cod frozen Sunday").assertExists()
    }

    @Test fun `no answer and nothing saved, only Try again, no offer to write a plan`() {
        var reloaded = 0; var started = 0
        compose.setContent { PrepContent(PrepState(loading = false, error = "Can't reach the meal-prep server.", unknown = true), wk, today,
            PrepActions(onStart = { started++ }, onReload = { reloaded++ })) }
        compose.onNodeWithText(NO_PLAN).assertDoesNotExist()
        compose.onNodeWithText("Write my prep plan").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, reloaded); assertEquals(0, started)
    }
}
