package dev.mealprep.app.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.mealprep.app.data.api.PlanEntry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val wk = LocalDate.parse("2026-10-11")
    private val entries = listOf(PlanEntry(21, "2026-10-11", 5, 2, title = "Chili"), PlanEntry(23, "2026-10-11", 1, null, title = "Cookies"))
    private val ui = WeekUi(wk, weekView(wk, entries), statusStrip(entries, false, null), ContextAction.BuildCart(wk), loading = false)

    @Test fun `shows nights, tray and the context button`() {
        var action: ContextAction? = null
        compose.setContent { WeekContent(ui, LocalDate.parse("2026-10-07"), { action = it }, { _, _ -> }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Tue 13").assertExists()
        compose.onNodeWithText("Chili").assertExists()
        compose.onNodeWithText("Cookies").assertExists()
        compose.onNodeWithText("Build cart").performClick()
        assertEquals(ContextAction.BuildCart(wk), action)
    }

    @Test fun `tapping a tray recipe lets you put it on a night`() {
        var placed: Pair<Int, Int?>? = null
        compose.setContent { WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { e, d -> placed = e.id to d }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Cookies").performClick()
        compose.onNodeWithText("Thu").performClick()
        assertEquals(23 to 4, placed)
    }

    @Test fun `a placed recipe can be moved to another night or back to the tray from its dialog`() {
        val moves = mutableListOf<Pair<Int, Int?>>()
        compose.setContent { WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { e, d -> moves += e.id to d }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Chili").performClick()
        compose.onNodeWithText("Move to").assertExists()
        compose.onNodeWithText("Tue").performClick()                                // where it already is: nothing
        compose.onNodeWithText("Fri").performClick()
        assertEquals(listOf(21 to 5), moves)
        compose.onNodeWithText("Chili").performClick()
        compose.onNode(hasText(NO_NIGHT) and hasClickAction()).performClick()      // the dialog's chip, not the tray heading
        assertEquals(listOf(21 to 5, 21 to null), moves)
    }

    @Test fun `holding a recipe picks it up (a buzz, the drag starts) instead of opening the dialog`() {
        val buzzes = mutableListOf<HapticFeedbackType>()
        val haptics = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { buzzes += hapticFeedbackType } }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Hold a recipe and drag it to a night (or back here), or tap it to move it.").assertExists()
        compose.onNodeWithText("Chili").performTouchInput { longClick() }
        assertEquals(listOf(HapticFeedbackType.LongPress), buzzes)            // the hold reached the drag start
        compose.onNodeWithText("Move to").assertDoesNotExist()
        compose.onNodeWithText("Chili").performClick()                        // and a tap still opens it
        compose.onNodeWithText("Move to").assertExists()
        assertEquals(1, buzzes.size)
    }

    @Test fun `while a recipe is held the zones say where it goes`() {
        val drag = WeekDrag()
        compose.setContent { WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, drag = drag) }
        compose.onNodeWithText("Drop here").assertDoesNotExist()
        drag.started(); drag.entered(Slot(0))
        compose.onNodeWithText("Drop here").assertExists()
        drag.entered(Slot.TRAY); drag.exited(Slot(0))
        compose.onNodeWithText("Drop here to take it off its night").assertExists()
        drag.ended()
        compose.onNodeWithText("Drop here to take it off its night").assertDoesNotExist()
    }

    @Test fun `an import waiting for the home network shows no spinner and can be cancelled`() {
        val id = java.util.UUID.randomUUID()
        var cancelled: java.util.UUID? = null
        compose.setContent { ImportCards(listOf(ImportUi.Waiting(id)), {}, {}, {}, onCancel = { cancelled = it }) }
        compose.onNodeWithText("Waiting for the home network — it will be sent automatically.").assertExists()
        compose.onNodeWithText("Reading recipe…").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(id, cancelled)
    }

    @Test fun `a sent cart can be reopened from the status strip`() {
        var opened: Any? = null
        val sent = ui.copy(strip = statusStrip(entries, true, null), sentDraftId = 9)
        compose.setContent { WeekContent(sent, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, { opened = it }, {}) }
        compose.onNodeWithText("✓ Cart sent").performClick()
        assertEquals(dev.mealprep.app.ui.nav.DraftRoute(9), opened)
    }

    @Test fun `an imported recipe that uses another page offers to photograph it`() {
        val id = java.util.UUID.randomUUID()
        var opened: Any? = null
        val done = ImportUi.Done(id, 3, "Wings", false, null, 1, 191, wk, "Batter for 24 crêpes, page 191")
        compose.setContent { ImportCards(listOf(done), {}, {}, { opened = it }, {}) }
        compose.onNodeWithText("This uses “Batter for 24 crêpes, page 191” — add a photo of page 191?").assertExists()
        compose.onNodeWithText("Not now").assertExists()
        compose.onNodeWithText("Add photo of p.191").performClick()
        assertEquals(dev.mealprep.app.ui.nav.CameraRoute("ref", 3, 1, 191), opened)
    }

    @Test fun `a recipe on the week that still misses a page offers it in its dialog`() {
        var opened: Any? = null
        compose.setContent {
            WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, { opened = it }, {},
                loadRef = { id -> if (id == 5) dev.mealprep.app.ui.camera.RefPrompt(5, 2, "Sauce, page 51", 51) else null })
        }
        compose.onNodeWithText("Chili").performClick()
        compose.onNodeWithText("Add photo of p.51").performClick()
        assertEquals(dev.mealprep.app.ui.nav.CameraRoute("ref", 5, 2, 51), opened)
    }

    @Test fun `a recipe saved to Recipes offers Add to a week`() {
        var opened: Any? = null
        val done = ImportUi.Done(java.util.UUID.randomUUID(), 4, "Lentil Soup", false, "Family 4/5", -1, 0)
        compose.setContent { ImportCards(listOf(done), {}, {}, { opened = it }, {}) }
        compose.onNodeWithText("Added Lentil Soup to Recipes").assertExists()
        compose.onNodeWithText("Family 4/5").assertExists()
        compose.onNodeWithText("Add to a week…").performClick()
        assertEquals(dev.mealprep.app.ui.nav.RecipeRoute(4, addToWeek = true), opened)
        compose.onNodeWithText("OK").assertExists()
    }

    @Test fun `an empty week points to Recipes instead of a share hint`() {
        var action: ContextAction? = null
        val empty = WeekUi(wk, weekView(wk, emptyList()), statusStrip(emptyList(), false, null), ContextAction.AddRecipes, loading = false)
        compose.setContent { WeekContent(empty, LocalDate.parse("2026-10-07"), { action = it }, { _, _ -> }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText(EMPTY_WEEK).assertExists()
        compose.onNodeWithText("○ Planned").assertExists()                                  // the status line stays
        compose.onNodeWithText("Choose from Recipes").performClick()
        assertEquals(ContextAction.AddRecipes, action)
        // Only the card: no tray, no seven empty nights, no drag hint, no Refresh.
        compose.onNodeWithText(NO_NIGHT).assertDoesNotExist()
        compose.onNodeWithText("Tue 13").assertDoesNotExist()
        compose.onNodeWithText("Hold a recipe", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
    }

    @Test fun `the week's next step is docked at the bottom, full width`() {
        var action: ContextAction? = null
        val prep = ui.copy(action = ContextAction.StartPrep(wk))
        compose.setContent { WeekContent(prep, LocalDate.parse("2026-10-07"), { action = it }, { _, _ -> }, { _, _ -> }, {}, {}, {}) }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithText("Start Sunday prep").fetchSemanticsNode().boundsInRoot
        assertTrue(button.bottom > root.bottom - 100f)                       // at the bottom of the screen
        compose.onNodeWithText("Start Sunday prep").performClick()
        assertEquals(ContextAction.StartPrep(wk), action)
        compose.onNodeWithText("Refresh").assertDoesNotExist()              // the week reloads on its own
    }

    @Test fun `a saved copy offers Refresh`() {
        var refreshed = 0
        val offline = ui.copy(offlineSince = java.time.Instant.parse("2026-10-07T12:00:00Z"))
        compose.setContent { WeekContent(offline, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, {}, { refreshed++ }) }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Refresh"))
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(1, refreshed)
    }

    @Test fun `the week header names the dates and steps to the previous and next week`() {
        var steps = ""
        var first by mutableStateOf(false)
        compose.setContent {
            WeekHeader(wk, LocalDate.parse("2026-10-07"), canBack = !first, canForward = true,
                onPrev = { steps += "<" }, onNext = { steps += ">" }, menu = emptyList(), onOpen = {})
        }
        compose.onNodeWithText("Next week").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Oct 11 – 17").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Oct 11 to 17")))
        compose.onNodeWithContentDescription("Previous week").assertTouchHeightIsEqualTo(48.dp).performClick()
        compose.onNodeWithContentDescription("Next week").assertTouchHeightIsEqualTo(48.dp).performClick()
        assertEquals("<>", steps)
        first = true                                                       // the oldest week: nothing before it
        compose.onNodeWithContentDescription("Previous week").assertIsNotEnabled()
    }

    @Test fun `a week that couldn't be loaded and has no saved copy shows only the message and Try again`() {
        var refreshed = 0
        val failed = WeekUi(wk, loading = false, error = "Can't reach the meal-prep server.")
        compose.setContent { WeekContent(failed, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, {}, { refreshed++ }) }
        compose.onNodeWithText("Can't reach the meal-prep server.").assertExists()
        compose.onNodeWithText(EMPTY_WEEK).assertDoesNotExist()
        compose.onNodeWithText("Build cart").assertDoesNotExist()                 // nothing docked either
        compose.onNodeWithText("Choose from Recipes").assertDoesNotExist()
        compose.onNodeWithText("○ Planned").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, refreshed)
    }

    @Test fun `all set is a line of text, not a button`() {
        val done = ui.copy(action = ContextAction.AllSet)
        compose.setContent { WeekContent(done, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("✓ All set for this week").assertExists().assertHasNoClickAction()
    }

    @Test fun `the status strip says done or not yet`() {
        compose.setContent { WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithContentDescription("Planned").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "done"))
        compose.onNodeWithContentDescription("Prep done").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not yet"))
    }

    @Test fun `the entry dialog's amounts are words, one choice`() {
        var scaled: Double? = null
        compose.setContent { WeekContent(ui, LocalDate.parse("2026-10-07"), {}, { _, _ -> }, { _, m -> scaled = m }, {}, {}, {}) }
        compose.onNodeWithText("Chili").performClick()
        compose.onNodeWithText("Amount").assertExists()
        compose.onNodeWithText("Normal").assertIsSelected()
        compose.onNodeWithText("Double").performClick()
        assertEquals(2.0, scaled)
    }

    @Test fun `many import cards scroll in their own area instead of pushing the week away`() {
        val cards = (1..8).map { ImportUi.Waiting(java.util.UUID.randomUUID()) }
        compose.setContent { androidx.compose.foundation.layout.Column { ImportCards(cards, {}, {}, {}, {}); androidx.compose.material3.Text("The week") } }
        compose.onNodeWithText("The week").assertIsDisplayed()
    }

    @Test fun `a failed page import is worded for a page`() {
        val f = ImportUi.Failed(java.util.UUID.randomUUID(), "That page is already part of the recipe.", false, dev.mealprep.app.work.ImportWorker.PAGES)
        compose.setContent { ImportCards(listOf(f), {}, {}, {}, {}) }
        compose.onNodeWithText("Couldn't add the page").assertExists()
        compose.onNodeWithText("Check the shopping list for its ingredients before adding it again.").assertExists()
    }
}
