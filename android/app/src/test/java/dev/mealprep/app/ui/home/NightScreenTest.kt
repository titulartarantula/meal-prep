package dev.mealprep.app.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.Extra
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.data.api.WeekNight
import dev.mealprep.app.ui.common.Access
import dev.mealprep.app.ui.common.LocalAccess
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** This week: a night's name and store items (2026-11-07). No `w411dp-h891dp` qualifier here: with it, a text field in a
 *  dialog never lets Compose go idle under Robolectric (the name dialog). */
@RunWith(RobolectricTestRunner::class)
class NightScreenTest {
    @get:Rule val compose = createComposeRule()
    private val wk = LocalDate.parse("2026-10-12")
    private val today = LocalDate.parse("2026-10-08")
    private val pizza = Extra(7, "2026-10-12", "frozen pizza", product = Product("PIZ", "Pepperoni Pizza", "Hearthstone"), quantity = 2, day = 4)
    private val entries = listOf(PlanEntry(21, "2026-10-12", 5, 2, title = "Chili"))
    private val nights = listOf(WeekNight(4, "Pizza night", listOf(pizza)))
    private val ui = WeekUi(wk, weekView(wk, entries), statusStrip(entries, false, nights), ContextAction.BuildCart(wk), nights = nights, loading = false)

    private fun show(u: WeekUi = ui, a: NightActions = NightActions(), access: Access = Access()) = compose.setContent {
        CompositionLocalProvider(LocalAccess provides access) {
            WeekContent(u, today, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, nightActions = a)
        }
    }
    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    private fun menu(day: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription("Options for $day"))
        compose.onNodeWithContentDescription("Options for $day").performClick()
    }

    @Test fun `a night shows its name and store items`() {
        show()
        scrollTo("Pizza night")
        compose.onNodeWithText("Pizza night").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("From the store").assertExists()
        compose.onNodeWithText("Hearthstone Pepperoni Pizza ×2").assertHeightIsAtLeast(48.dp)
    }

    @Test fun `the night menu offers naming it`() {
        show()
        menu("Tuesday")
        compose.onNodeWithText(NAME_NIGHT).assertExists()
        compose.onNodeWithText(ADD_STORE_ITEMS).assertExists()
        compose.onNodeWithText(CLEAR_NAME).assertDoesNotExist()
    }

    @Test fun `a named night's menu offers renaming and clearing it`() {
        show()
        menu("Friday")
        compose.onNodeWithText(RENAME_NIGHT).assertExists()
        compose.onNodeWithText(CLEAR_NAME).assertExists()
    }

    @Test fun `name a night in its dialog`() {
        val names = mutableListOf<Pair<Int, String>>()
        compose.setContent { NameNightDialog(1, null, onSave = { names += 1 to it }, onDismiss = {}) }
        compose.onNodeWithText("Name Tuesday").assertExists()
        compose.onNodeWithText("Name of the night").performTextReplacement("x".repeat(61))
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name of the night").performTextReplacement("  Leftovers ")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf(1 to "Leftovers"), names)
    }

    @Test fun `rename or clear a night by tapping its name`() {
        val names = mutableListOf<Pair<Int, String>>()
        show(a = NightActions(onName = { d, t -> names += d to t }))
        scrollTo("Pizza night")
        compose.onNodeWithText("Pizza night").performClick()
        compose.onNodeWithText("Rename Friday").assertExists()
        compose.onNodeWithText(CLEAR_NAME).performClick()
        assertEquals(listOf(4 to ""), names)
    }

    @Test fun `add store items opens the chooser for that night`() {
        var added: Int? = null
        show(a = NightActions(onAddItems = { added = it }))
        menu("Sunday")
        compose.onNodeWithText(ADD_STORE_ITEMS).performClick()
        assertEquals(6, added)
    }

    @Test fun `a store item's quantity, move and removal`() {
        val qty = mutableListOf<Int>(); val moves = mutableListOf<Int?>(); val removed = mutableListOf<Int>()
        show(a = NightActions(onQuantity = { _, q -> qty += q }, onMove = { _, d -> moves += d }, onRemove = { removed += it.id }))
        scrollTo("Hearthstone Pepperoni Pizza ×2")
        compose.onNodeWithText("Hearthstone Pepperoni Pizza ×2").performClick()
        compose.onNodeWithContentDescription("One more Hearthstone Pepperoni Pizza").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf(3), qty)
        compose.onNodeWithText("Hearthstone Pepperoni Pizza ×2").performClick()
        compose.onNodeWithText("Sat").performClick()
        assertEquals(listOf<Int?>(5), moves)
        compose.onNodeWithText("Hearthstone Pepperoni Pizza ×2").performClick()
        compose.onNodeWithText(WHOLE_WEEK).performClick()
        assertEquals(listOf(5, null), moves)
        compose.onNodeWithText("Hearthstone Pepperoni Pizza ×2").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf(7), removed)
    }

    @Test fun `a reader sees names and items but can't change them`() {
        show(access = Access(setOf("plan.read")))
        compose.onNodeWithContentDescription("Options for Friday").assertDoesNotExist()
        scrollTo("Pizza night")
        compose.onNodeWithText("Pizza night").assertExists()
        compose.onNodeWithText("Hearthstone Pepperoni Pizza ×2").performClick()
        compose.onNodeWithText("Done").assertDoesNotExist()
    }

    @Test fun `an empty week for a reader is only its card`() {
        val empty = WeekUi(wk, weekView(wk, emptyList()), statusStrip(emptyList(), false), ContextAction.AddRecipes, loading = false)
        show(empty, access = Access(setOf("plan.read")))
        compose.onNodeWithText(EMPTY_WEEK).assertExists()
        compose.onNodeWithText("Wed 14").assertDoesNotExist()
    }
}
