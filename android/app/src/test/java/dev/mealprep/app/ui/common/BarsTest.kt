package dev.mealprep.app.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.fixture
import dev.mealprep.app.ui.card.CardContent
import dev.mealprep.app.ui.card.CardState
import dev.mealprep.app.ui.card.CardUiActions
import dev.mealprep.app.ui.cart.StaplesContent
import dev.mealprep.app.ui.cart.StaplesState
import dev.mealprep.app.ui.library.RecipeContent
import dev.mealprep.app.ui.library.RecipeState
import dev.mealprep.app.ui.prep.PrepActions
import dev.mealprep.app.ui.prep.PrepContent
import dev.mealprep.app.ui.prep.PrepState
import dev.mealprep.app.ui.rating.RatingContent
import dev.mealprep.app.ui.rating.RatingState
import dev.mealprep.app.ui.setup.SettingsContent
import dev.mealprep.app.ui.share.ShareContent
import dev.mealprep.app.ui.share.ShareState
import dev.mealprep.app.core.ShareInput
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** One top bar with Back on every screen that isn't a tab (UX/WCAG review 0.7, finding 9). */
@RunWith(RobolectricTestRunner::class)
class BarsTest {
    @get:Rule val compose = createComposeRule()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private var back = 0

    /** [title] is the screen's heading, Navigate up is a 48 dp target that leaves, and no Close / Done / Cancel is left. */
    private fun pushed(title: String, content: @Composable (onBack: () -> Unit) -> Unit) {
        compose.setContent { content { back++ } }
        compose.onNodeWithText(title).assert(heading)
        compose.onNodeWithContentDescription(NAVIGATE_UP).assertTouchHeightIsEqualTo(48.dp).assertTouchWidthIsEqualTo(48.dp).performClick()
        assertEquals(1, back)
        listOf("Close", "Done", "Cancel").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun `recipe detail`() {
        val r = Http.json.decodeFromString(Recipe.serializer(), fixture("recipe_11.json"))
        pushed(r.title) { b -> RecipeContent(RecipeState(r, loading = false), { _, _ -> }, {}, {}, {}, onBack = b) }
        compose.onNodeWithText("Add to a week").assertExists()            // docked at the bottom
    }

    @Test fun `rating`() = pushed("How was Tuesday's Chili?") { b ->
        RatingContent(RatingState(title = "Chili", night = "Tuesday", loading = false), {}, {}, {}, {}, {}, onBack = b)
    }

    @Test fun `staples`() = pushed("Staples") { b -> StaplesContent(StaplesState(loading = false), { true }, {}, { _, _ -> }, onBack = b) }

    @Test fun `settings`() = pushed("Settings") { b -> SettingsContent(onBack = b, extra = { Text("Notifications") }, server = {}) }

    @Test fun `sunday prep`() = pushed("Sunday prep") { b ->
        PrepContent(PrepState(loading = false), LocalDate.parse("2026-10-11"), LocalDate.parse("2026-10-07"), PrepActions(onBack = b))
    }

    @Test fun `cook card`() {
        val card = Http.json.decodeFromString(CookCard.serializer(), fixture("cook_card.json"))
        pushed(card.title) { b -> CardContent(CardState(card, loading = false), emptySet(), CardUiActions(onBack = b)) }
    }

    @Test fun `share`() = pushed("Save a recipe") { b ->
        ShareContent(ShareState(ShareInput.NytLink("https://cooking.nytimes.com/recipes/1015819-chili", "")), {}, b, {})
    }
}

/** Real text measuring (Robolectric's legacy graphics give every line room to spare). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackTopBarWrapTest {
    @get:Rule val compose = createComposeRule()
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    @Test fun `a long title wraps to two lines instead of being cut at one`() {
        val long = "Sheet-pan Pizza with Roasted Peppers, Olives, Fresh Basil and a Very Long Name"
        compose.setContent { BackTopBar(long, {}, subtitle = "Tue Oct 13") }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(long).assert(heading)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(2, layouts.single().lineCount)
        assertTrue(layouts.single().hasVisualOverflow)                     // longer still: ellipsis after two lines
        compose.onNodeWithText("Tue Oct 13").assertExists()
    }
}
