package dev.mealprep.app.ui.cart

import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.fixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DraftScreenTest {
    @get:Rule val compose = createComposeRule()
    private val ready = Http.json.decodeFromString(Draft.serializer(), fixture("draft_ready.json"))

    @Test fun `ready draft shows lines, total and Send`() {
        var sent = 0
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions(onSend = { sent++ })) }
        compose.onNodeWithText("Yellow Onions · 1.36 kg bag  $3.99").assertExists()
        compose.onNodeWithText("No product found — tap Swap to search.").assertExists()
        compose.onNodeWithText("About $3.99 · 1 item").assertExists()
        compose.onNodeWithText("Need 3 · diced").assertExists()        // amount and prep under the item name
        compose.onNodeWithText("Need 900 g").assertExists()
        compose.onNodeWithText("Send to Loblaws").performClick()
        assertEquals(1, sent)
    }

    @Test fun `sent draft opens Loblaws with its cart id`() {
        var opened: String? = null
        val sent = ready.copy(status = "sent", pcxCartId = "24b34302-2508-4795-ab5d-2f2f9a7de03c")
        compose.setContent { DraftContent(DraftState(sent, loading = false), DraftActions(onLoblaws = { opened = it })) }
        compose.onAllNodesWithText("Swap").assertAll(isNotEnabled())   // lines are shown, editing is off
        compose.onNodeWithText("Open in Loblaws").performClick()
        assertEquals("24b34302-2508-4795-ab5d-2f2f9a7de03c", opened)
    }

    @Test fun `failed draft offers to build again for its weeks`() {
        var weeks: List<String>? = null
        val failed = Draft(7, "failed", error = "Every product search failed. Try again later.", weeks = listOf("2026-10-11"))
        compose.setContent { DraftContent(DraftState(failed, loading = false, error = failed.error), DraftActions(onRebuild = { weeks = it })) }
        compose.onNodeWithText("Every product search failed. Try again later.").assertExists()
        compose.onNodeWithText("Build it again").performClick()
        assertEquals(listOf("2026-10-11"), weeks)
    }

    @Test fun `building draft shows progress`() {
        val building = Draft(7, "building", progress = dev.mealprep.app.data.api.Progress(3, 11))
        compose.setContent { DraftContent(DraftState(building, loading = false), DraftActions()) }
        compose.onNodeWithText("Finding products… 3 of 11").assertExists()
    }

    @Test fun `nothing kept means nothing to send`() {
        val none = ready.copy(lines = ready.lines.map { it.copy(removed = true) })
        compose.setContent { DraftContent(DraftState(none, loading = false), DraftActions()) }
        compose.onNodeWithText("Send to Loblaws").assertIsNotEnabled()
    }

    @Test fun `planner explanation shows under the product when the server sends it`() {
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions()) }
        compose.onNodeWithText("Need 3 (≈ 600 g) → 1 × 1.36 kg").assertExists()
        val (matched, unmatched) = ready.lines
        assertEquals(Triple(1, false, "Need 3 (≈ 600 g) → 1 × 1.36 kg"), Triple(matched.packsMin, matched.needsCheck, matched.why))
        assertEquals(Triple(null, false, null), Triple(unmatched.packsMin, unmatched.needsCheck, unmatched.why))  // older server
    }

    @Test fun `need text`() {
        val line = ready.lines[0]
        assertEquals("Need ⅚ cup · warmed", needText(line.copy(qty = 0.83, unit = "cup", prep = "warmed")))
        assertEquals("finely grated", needText(line.copy(qty = null, unit = null, prep = "finely grated")))
        assertEquals(null, needText(line.copy(qty = null, unit = null, prep = null)))
    }
}
