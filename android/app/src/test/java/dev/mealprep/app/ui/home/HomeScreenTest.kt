package dev.mealprep.app.ui.home

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    @Test fun `a failed page import is worded for a page`() {
        val f = ImportUi.Failed(java.util.UUID.randomUUID(), "That page is already part of the recipe.", false, dev.mealprep.app.work.ImportWorker.PAGES)
        compose.setContent { ImportCards(listOf(f), {}, {}, {}, {}) }
        compose.onNodeWithText("Couldn't add the page").assertExists()
        compose.onNodeWithText("Check the shopping list for its ingredients before adding it again.").assertExists()
    }
}
