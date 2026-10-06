package dev.mealprep.app.ui.cart

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import dev.mealprep.app.ui.common.NAVIGATE_UP
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
    private val today = java.time.LocalDate.parse("2026-10-07")

    @Test fun `ready draft leads each line with the product and price, then one quiet line`() {
        var sent = 0
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions(onSend = { sent++ }), today = today) }
        compose.onNodeWithText("Yellow Onions · 1.36 kg bag").assertExists()
        compose.onNodeWithText("$3.99").assertExists()
        compose.onNodeWithText("onion · need 3 · diced · 2 recipes").assertExists()   // list name, amount, how many recipes
        compose.onNodeWithText("No product found for ground beef").assertExists()
        compose.onNodeWithText("ground beef · need 900 g · Chili").assertExists()      // one recipe: by name
        compose.onNodeWithText("About $3.99 · 1 item").assertExists()
        compose.onNodeWithText("Cart").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Next week (Oct 11)").assertExists()                      // the cart's week under the title
        compose.onNodeWithText("Send to Loblaws").performClick()
        assertEquals(1, sent)
    }

    @Test fun `a stale ready cart is an older cart - read-only, Send off, Build a new cart instead`() {
        var rebuilt: List<String>? = null
        var sent = 0
        compose.setContent {
            DraftContent(DraftState(ready.copy(stale = true), loading = false),
                DraftActions(onSend = { sent++ }, onRebuild = { rebuilt = it }), today = today)
        }
        compose.onNodeWithText("Older cart").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText(STALE_CART).assertExists()
        compose.onNodeWithText("Send to Loblaws").assertIsNotEnabled().performClick()
        assertEquals(0, sent)
        compose.onNodeWithContentDescription("One more onion").assertIsNotEnabled()
        compose.onNodeWithText(BUILD_NEW_CART).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("2026-10-11"), rebuilt)
        assertEquals(false, DraftState(ready.copy(stale = true), loading = false).canSend)
        assertEquals(true, DraftState(ready, loading = false).canSend)
    }

    @Test fun `a stale sent cart says so and still opens in Loblaws`() {
        val sent = Http.json.decodeFromString(Draft.serializer(), fixture("draft_3_sent.json")).copy(stale = true)
        var opened: String? = null
        compose.setContent { DraftContent(DraftState(sent, loading = false), DraftActions(onLoblaws = { opened = it }), today = today) }
        compose.onNodeWithText("Older cart").assertExists()
        compose.onNodeWithText(STALE_CART).assertExists()
        compose.onNodeWithText("Open in Loblaws").performClick()
        assertEquals(sent.pcxCartId, opened)
    }

    @Test fun `an up-to-date cart has no banner`() {
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions(), today = today) }
        compose.onNodeWithText(STALE_CART).assertDoesNotExist()
        compose.onNodeWithText(BUILD_NEW_CART).assertDoesNotExist()
    }

    @Test fun `a line with no product searches Loblaws directly`() {
        var swapped: String? = null
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions(onSwap = { swapped = it.name })) }
        compose.onNodeWithText(SEARCH_LOBLAWS).performClick()
        assertEquals("ground beef", swapped)
    }

    @Test fun `the line menu says why and for which recipes, and swaps or removes`() {
        val calls = mutableListOf<String>()
        compose.setContent {
            DraftContent(DraftState(ready, loading = false), DraftActions(onSwap = { calls += "swap ${it.name}" },
                onRemove = { l, r -> calls += "remove ${l.name} $r" }))
        }
        compose.onNodeWithContentDescription("More for onion").performClick()
        compose.onNodeWithText("Need 3 (≈ 600 g) → 1 × 1.36 kg").assertExists()
        compose.onNodeWithText("For Chili, Fish soup").assertExists()
        compose.onNodeWithText("Product: Remembered").assertExists()
        compose.onNodeWithText("Swap product").performClick()
        compose.onNodeWithContentDescription("More for onion").performClick()
        compose.onNodeWithText("Remove from cart").performClick()
        assertEquals(listOf("swap onion", "remove onion true"), calls)
    }

    @Test fun `a removed line is struck through and offers Put back`() {
        val removed = ready.copy(lines = listOf(ready.lines[0].copy(removed = true)))
        var put: Boolean? = null
        compose.setContent { DraftContent(DraftState(removed, loading = false), DraftActions(onRemove = { _, r -> put = r })) }
        compose.onNodeWithContentDescription("One more onion").assertDoesNotExist()
        compose.onNodeWithText("Put back").performClick()
        assertEquals(false, put)
    }

    @Test fun `quantity buttons say which item for TalkBack`() {
        var qty: Int? = null
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions(onQty = { _, q -> qty = q })) }
        compose.onNodeWithContentDescription("One more onion").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(2, qty)
        compose.onNodeWithContentDescription("One fewer onion").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Quantity 1").assertExists()
    }

    @Test fun `sent draft opens Loblaws with its cart id`() {
        var opened: String? = null
        val sent = ready.copy(status = "sent", pcxCartId = "24b34302-2508-4795-ab5d-2f2f9a7de03c")
        compose.setContent { DraftContent(DraftState(sent, loading = false), DraftActions(onLoblaws = { opened = it })) }
        compose.onNodeWithContentDescription("One more onion").assertIsNotEnabled()   // lines are shown, editing is off
        compose.onNodeWithText(SEARCH_LOBLAWS).assertIsNotEnabled()
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

    @Test fun `planner explanation is in the line's menu when the server sends it`() {
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions()) }
        compose.onNodeWithText("Need 3 (≈ 600 g) → 1 × 1.36 kg").assertDoesNotExist()
        compose.onNodeWithContentDescription("More for onion").performClick()
        compose.onNodeWithText("Need 3 (≈ 600 g) → 1 × 1.36 kg").assertExists()
        val (matched, unmatched) = ready.lines
        assertEquals(Triple(1, false, "Need 3 (≈ 600 g) → 1 × 1.36 kg"), Triple(matched.packsMin, matched.needsCheck, matched.why))
        assertEquals(Triple(null, false, null), Triple(unmatched.packsMin, unmatched.needsCheck, unmatched.why))  // older server
    }

    @Test fun `back leaves the cart`() {
        var back = 0
        compose.setContent { DraftContent(DraftState(ready, loading = false), DraftActions(onBack = { back++ })) }
        compose.onNodeWithContentDescription(NAVIGATE_UP).performClick()
        assertEquals(1, back)
    }

    @Test fun `line detail and weeks title`() {
        val line = ready.lines[0]
        assertEquals("onion · need 3 · diced · 2 recipes", lineDetail(line))
        assertEquals("onion · need 3 · diced · 2 recipes · $3.99 each", lineDetail(line.copy(quantity = 2)))
        assertEquals("milk", lineDetail(line.copy(name = "milk", qty = null, prep = null, recipes = emptyList())))
        assertEquals("Next week (Oct 11)", weeksTitle(listOf("2026-10-11"), today))
        assertEquals("Oct 11 + Oct 18", weeksTitle(listOf("2026-10-18", "2026-10-11"), today))
        assertEquals(null, weeksTitle(emptyList(), today))
    }

    @Test fun `need text`() {
        val line = ready.lines[0]
        assertEquals("Need ⅚ cup · warmed", needText(line.copy(qty = 0.83, unit = "cup", prep = "warmed")))
        assertEquals("finely grated", needText(line.copy(qty = null, unit = null, prep = "finely grated")))
        assertEquals(null, needText(line.copy(qty = null, unit = null, prep = null)))
    }
}
