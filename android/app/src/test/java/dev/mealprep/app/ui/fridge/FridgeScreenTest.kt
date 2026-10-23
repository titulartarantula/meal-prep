package dev.mealprep.app.ui.fridge

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.FridgeItem
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.fixture
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FridgeScreenTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.parse("2026-10-07")
    private val items = Http.json.decodeFromString(ListSerializer(FridgeItem.serializer()), fixture("fridge.json"))
    private val read = Http.json.decodeFromString(FridgeRead.serializer(), fixture("fridge_read_ready.json"))

    private fun scrollTo(said: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(said))
        compose.onNodeWithContentDescription(said).assertIsDisplayed()
    }

    private fun show(state: FridgeState, a: FridgeActions = FridgeActions()) =
        compose.setContent { FridgeContent(state, today, a) }

    @Test fun `rows say the amount, when it was checked and what to use soon`() {
        show(FridgeState(items = items, loading = false))
        compose.onNodeWithContentDescription("Eggs, 6, as of yesterday, use by October 20").assertExists()
        compose.onNodeWithContentDescription("Cilantro, 1 bunch, last checked 9 days ago, use soon").assertExists()
        scrollTo("Spinach, about 1 bag, as of today, from a photo")
        scrollTo("Yogurt, as of today")
        compose.onNodeWithContentDescription("More for Eggs").assertHeightIsAtLeast(48.dp)
    }

    @Test fun `tapping a row opens its editor, the menu has still right and remove`() {
        val calls = mutableListOf<String>()
        show(FridgeState(items = items, loading = false), FridgeActions(onConfirm = { calls += "confirm $it" },
            onRemove = { calls += "remove ${it.id}" }))
        compose.onNodeWithContentDescription("Eggs, 6, as of yesterday, use by October 20").performClick()
        compose.onNodeWithText("Edit Eggs").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("More for Eggs").performClick()
        compose.onNodeWithText(STILL_RIGHT).performClick()
        compose.onNodeWithContentDescription("More for Eggs").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("confirm 1", "remove 1"), calls)
    }

    @Test fun `typing offers the shopping list's names and a pick opens the form as from the list`() {
        val saved = mutableListOf<FridgeForm>()
        show(FridgeState(items = items, loading = false, names = listOf("whole milk", "milk chocolate")),
            FridgeActions(onSave = { saved += it; true }))
        compose.onNodeWithText("Add to the fridge").performTextInput("milk")
        compose.onNodeWithText("whole milk").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("How many / how much").assertIsDisplayed()   // the form
        compose.onNodeWithText(FRIDGE_SAVE).performClick()
        assertEquals(listOf(FridgeForm(name = "whole milk", fromList = true)), saved)
    }

    @Test fun `a duplicate offers to edit the item already there`() {
        show(FridgeState(items = items, loading = false, exists = ApiError.Exists("Already in the fridge: 6 Eggs", 1)))
        compose.onNodeWithText("Already in the fridge: 6 Eggs").assertIsDisplayed()
        compose.onNodeWithText("Edit it").performClick()
    }

    @Test fun `cards for a read in progress and one to review`() {
        val opened = mutableListOf<Int>()
        show(FridgeState(items = items, loading = false, read = read), FridgeActions(onReview = { opened += it }))
        compose.onNodeWithText("Review 5 changes from your photos").assertExists()
        compose.onNodeWithText("Review").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(12), opened)
    }

    @Test fun `reading card`() {
        show(FridgeState(items = items, loading = false, read = read.copy(status = "reading", proposals = emptyList())))
        compose.onNodeWithText(READING_PHOTOS).assertExists()
    }

    @Test fun `empty fridge says what to add and suggest is off`() {
        show(FridgeState(loading = false))
        compose.onNodeWithText(FridgeLogic.EMPTY).assertIsDisplayed()
        compose.onNodeWithText(SUGGEST_DINNERS).assertIsNotEnabled()
        compose.onNodeWithText(TAKE_A_PHOTO).assertHeightIsAtLeast(48.dp)
    }

    @Test fun `offline banner`() {
        show(FridgeState(items = items, loading = false, offlineSince = Instant.parse("2026-10-07T12:00:00Z")))
        compose.onNodeWithText("Offline", substring = true).assertExists()
    }

    @Config(fontScale = 2f)
    @Test fun `at 200 percent text the rows wrap and both buttons stay`() {
        show(FridgeState(items = items, loading = false))
        compose.onNodeWithContentDescription("Cilantro, 1 bunch, last checked 9 days ago, use soon").assertIsDisplayed()
        scrollTo("Spinach, about 1 bag, as of today, from a photo")
        compose.onNodeWithText(SUGGEST_DINNERS).assertIsDisplayed()
        compose.onNodeWithText(TAKE_A_PHOTO).assertIsDisplayed()
    }
}
