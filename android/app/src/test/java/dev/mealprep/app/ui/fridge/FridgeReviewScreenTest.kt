package dev.mealprep.app.ui.fridge

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.fixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class FridgeReviewScreenTest {
    @get:Rule val compose = createComposeRule()
    private val read = Http.json.decodeFromString(FridgeRead.serializer(), fixture("fridge_read_ready.json"))
    private val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    private fun show(state: ReviewState, a: ReviewActions = ReviewActions()) = compose.setContent { ReviewContent(state, a) }
    private fun row(text: String) = compose.onNode(hasText(text) and isToggleable())
    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test fun `sections with counts, rows as checkboxes with the change in words`() {
        val toggled = mutableListOf<String>()
        show(ReviewState(read = read, loading = false, ticks = ReviewLogic.ticks(read)), ReviewActions(onToggle = { toggled += it }))
        compose.onNodeWithText("New (2)").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        row("Change Eggs: 6 → about 4 (estimate)").assertIsOn().assert(checkbox).assertHeightIsAtLeast(48.dp)
        row("Add · Lemons · about 2 (estimate) · door · not sure").assertIsOff()
        scrollTo("Remove Cilantro? Not seen in the photos")
        row("Remove Cilantro? Not seen in the photos").assertIsOff().performClick()
        assertEquals(listOf("p5"), toggled)
        compose.onNodeWithText("Save 3 changes").assertIsEnabled()
    }

    @Test fun `nothing ticked, nothing to save`() {
        show(ReviewState(read = read, loading = false, ticks = emptySet()))
        compose.onNodeWithText("Save 0 changes").assertIsNotEnabled()
    }

    @Test fun `edit opens the amount, discard is in the menu`() {
        val calls = mutableListOf<String>()
        show(ReviewState(read = read, loading = false, ticks = ReviewLogic.ticks(read)),
            ReviewActions(onEdit = { pid, e -> calls += "$pid ${e.qty} ${e.amountEdited}" }, onDiscard = { calls += "discard" }))
        compose.onAllNodesWithText("Edit")[0].performClick()
        compose.onNodeWithText("Edit Whole milk").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText(DISCARD_ALL).performClick()
        assertEquals(listOf("p3 1.0 false", "discard"), calls)
    }

    @Test fun `a read in progress and a failed read`() {
        show(ReviewState(read = read.copy(status = "reading", proposals = emptyList()), loading = false))
        compose.onNodeWithText(READING_PHOTOS).assertExists()
    }

    @Test fun `a failed read offers Try again and new photos`() {
        val calls = mutableListOf<String>()
        show(ReviewState(read = read.copy(status = "failed", error = "Couldn't read the fridge in those photos.", proposals = emptyList()),
            loading = false, canRetry = true), ReviewActions(onRetry = { calls += "retry" }, onNewPhotos = { calls += "new" }))
        compose.onNodeWithText("Couldn't read the fridge in those photos.").assertExists()
        compose.onNodeWithText("Try again").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(TAKE_NEW_PHOTOS).performClick()
        assertEquals(listOf("retry", "new"), calls)
    }

    @Config(fontScale = 2f)
    @Test fun `at 200 percent the rows wrap and the button stays`() {
        show(ReviewState(read = read, loading = false, ticks = ReviewLogic.ticks(read)))
        row("Change Eggs: 6 → about 4 (estimate)").assertIsDisplayed()
        compose.onNodeWithText("Save 3 changes").assertIsDisplayed()
    }
}
