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
}
