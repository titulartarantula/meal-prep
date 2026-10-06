package dev.mealprep.app.ui.cart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.data.api.Staple
import dev.mealprep.app.ui.common.WeekOption
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListScreenTest {
    @get:Rule val compose = createComposeRule()
    private val oct4 = LocalDate.parse("2026-10-04")
    private val oct11 = LocalDate.parse("2026-10-11")
    private val oct18 = LocalDate.parse("2026-10-18")
    private val options = listOf(
        WeekOption(oct4, "This week (Oct 4)", null),
        WeekOption(oct11, "Next week (Oct 11)", "2 recipes · cart sent"),
        WeekOption(oct18, "Week of Oct 18", "1 recipe"),
    )
    private val selector = hasText("Shopping for:") and hasClickAction()
    private fun week(label: String) = hasText(label) and isToggleable()

    /** The screen with a real week toggle (the view model's: the last week can't be unticked). */
    private fun show(initial: Set<LocalDate>): () -> Set<LocalDate> {
        var weeks by mutableStateOf(initial)
        compose.setContent {
            ListContent(
                ListState(weeks = weeks, options = options, loading = false,
                    items = listOf(ListItem("onion|count", "onion", qty = 3.0)),
                    staples = listOf(Staple(1, "milk", 2.0, "L")), ticked = setOf(1)),
                onWeek = { w -> (if (w in weeks) weeks - w else weeks + w).takeIf { it.isNotEmpty() }?.let { weeks = it } },
                onToggle = {}, onBuild = {}, today = LocalDate.parse("2026-10-07"),
            )
        }
        return { weeks }
    }

    @Test fun `a stale cart card builds a new cart first and opens the old one second`() {
        val old = dev.mealprep.app.data.api.Draft(3, "sent", stale = true)
        val calls = mutableListOf<String>()
        compose.setContent {
            ListContent(ListState(weeks = setOf(oct11), options = options, loading = false, existing = old,
                items = listOf(ListItem("onion|count", "onion", qty = 3.0))),
                onWeek = {}, onToggle = {}, onBuild = { calls += "build" }, onOpenDraft = { calls += "open $it" },
                today = LocalDate.parse("2026-10-07"))
        }
        compose.onNodeWithText("You changed this week's recipes after the cart was made.").assertExists()
        compose.onNodeWithText("A cart for this week was already sent to Loblaws.").assertDoesNotExist()
        compose.onNodeWithText("Open cart").assertDoesNotExist()
        compose.onNodeWithText(BUILD_NEW_CART).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(OPEN_OLD_CART).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("build", "open 3"), calls)
    }

    @Test fun `an up-to-date sent cart only opens`() {
        compose.setContent {
            ListContent(ListState(weeks = setOf(oct11), options = options, loading = false,
                existing = dev.mealprep.app.data.api.Draft(3, "sent"), items = listOf(ListItem("onion|count", "onion", qty = 3.0))),
                onWeek = {}, onToggle = {}, onBuild = {}, today = LocalDate.parse("2026-10-07"))
        }
        compose.onNodeWithText("A cart for this week was already sent to Loblaws.").assertExists()
        compose.onNodeWithText("Open cart").assertExists()
        compose.onNodeWithText(BUILD_NEW_CART).assertDoesNotExist()
    }

    @Test fun `the week selector is one line with the chosen week, and a dropdown role`() {
        show(setOf(oct11))
        compose.onNode(selector and hasText("Next week (Oct 11)")).assertExists()
        compose.onNode(selector and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.DropdownList)).assertExists()
        compose.onNode(week("Week of Oct 18")).assertDoesNotExist()     // the menu starts closed
        compose.onNodeWithText("milk · 2 L").assertExists()               // staples and the list get the room
        compose.onNodeWithText("Build cart (1 items)").assertExists()
    }

    @Test fun `the menu lists weeks with ticks and their details, and ticking applies at once`() {
        val weeks = show(setOf(oct11))
        compose.onNode(selector).performClick()
        compose.onNode(week("Next week (Oct 11)")).assertIsOn()
        compose.onNode(week("This week (Oct 4)")).assertIsOff()
        compose.onNodeWithText("2 recipes · cart sent").assertExists()
        compose.onNode(week("Week of Oct 18")).performClick()
        assertEquals(setOf(oct11, oct18), weeks())
        compose.onNode(week("Week of Oct 18")).assertIsOn()               // still open for another tick
        compose.onNode(selector and hasText("Oct 11 + Oct 18")).assertExists()
        compose.onNode(week("This week (Oct 4)")).performClick()
        compose.onNode(selector and hasText("3 weeks")).assertExists()
        compose.onNode(week("Next week (Oct 11)")).performClick()
        compose.onNode(week("Next week (Oct 11)")).assertIsOff()
        compose.onNode(selector and hasText("Oct 4 + Oct 18")).assertExists()
    }

    @Test fun `week labels`() {
        assertEquals("Next week (Oct 11)", weeksLabel(setOf(oct11), options))
        assertEquals("Week of Nov 29", weeksLabel(setOf(LocalDate.parse("2026-11-29")), options))   // outside the options
        assertEquals("Oct 11 + Oct 18", weeksLabel(setOf(oct18, oct11), options))
        assertEquals("3 weeks", weeksLabel(setOf(oct4, oct11, oct18), options))
    }
}
