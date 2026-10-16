package dev.mealprep.app.ui.cart

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.Choices
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.fixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class ChooserScreenTest {
    @get:Rule val compose = createComposeRule()
    private val page = Http.json.decodeFromString(Choices.serializer(), fixture("choices.json"))
    private val line = Http.json.decodeFromString(Draft.serializer(), fixture("draft_ready.json")).lines[0]
    private val state = ChooserState(line = line, editable = true, shownTerm = page.term, choices = page.choices,
        hasMore = true, loading = false)

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    @Test fun `cards show the current pick, prices, offers, last time, better value and the Ad label`() {
        compose.setContent { ChooserContent(state, ChooserActions()) }
        compose.onNodeWithText("Choose onion").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Current pick").assertExists()
        compose.onNodeWithText("$8.99 · $2.25/100 g").assertExists()
        compose.onNodeWithText("Better value: 400 g at $1.22/100 g").assertExists()
        compose.onNodeWithText("Last bought", substring = true).assertExists()
        scrollTo("SAVE $1.12")
        compose.onNodeWithText("SAVE $1.12 · ends Oct 21").assertExists()
        scrollTo("PC Optimum")
        compose.onNodeWithText("PC Optimum 4,000 pts · ends Nov 4").assertExists()
        compose.onNodeWithContentDescription("Ad, sponsored by the brand").assertExists()
        compose.onNodeWithText("6 products for “old cheddar”").assertExists()
    }

    @Test fun `out of stock is shown last, greyed and not selectable - even the pinned usual product`() {
        var opened = 0
        compose.setContent { ChooserContent(state, ChooserActions(onOpen = { opened++ })) }
        scrollTo(OUT_THIS_WEEK)
        compose.onNodeWithText(OUT_THIS_WEEK).assertExists()
        compose.onNodeWithText("PC · Aged White Cheddar").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("PC · Aged White Cheddar").performClick()
        assertEquals(0, opened)
        compose.onNodeWithText(ALWAYS_BOUGHT).assertExists()
    }

    @Test fun `a card opens its detail, filter chips toggle, Show more asks for more`() {
        val opened = mutableListOf<String>()
        val filters = mutableListOf<ChoiceFilter>()
        var more = 0
        compose.setContent { ChooserContent(state, ChooserActions(onOpen = { opened += it.product.code }, onFilter = { filters += it }, onMore = { more++ })) }
        compose.onNodeWithText("PC · Old Cheddar Cheese").performClick()
        assertEquals(listOf("21290257_EA"), opened)
        compose.onNodeWithText("On sale").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(ChoiceFilter.SALE), filters)
        scrollTo(SHOW_MORE)
        compose.onNodeWithText(SHOW_MORE).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, more)
    }

    @Test fun `filters that match nothing say so`() {
        compose.setContent { ChooserContent(state.copy(filters = setOf(ChoiceFilter.BOUGHT, ChoiceFilter.SALE)), ChooserActions()) }
        compose.onNodeWithText("None of these match the filters — Show more to look further.").assertExists()
    }

    @Test fun `detail - photos, description, stepper within limits, pin, Use this`() {
        var used = 0; var more = 0; var pin: Boolean? = null
        val d = ChoiceDetail(page.choices[0], 2, pin = false)
        compose.setContent { ChooserContent(state.copy(detail = d), ChooserActions(onUse = { used++ }, onMoreQty = { more++ }, onPin = { pin = it })) }
        compose.onNodeWithText("PC · Old Cheddar Cheese").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Photo 1 of 2").assertExists()
        compose.onNodeWithContentDescription("Next photo").assertTouchHeightIsEqualTo(48.dp)
        compose.onNodeWithContentDescription("Previous photo").assertIsNotEnabled()
        compose.onNodeWithText("Rich and tangy.", substring = true).assertExists()
        compose.onNodeWithContentDescription("Quantity 2").assertExists()
        compose.onNodeWithText("About $17.98").assertExists()
        compose.onNodeWithContentDescription("One more onion").assertTouchHeightIsEqualTo(48.dp).performClick()
        assertEquals(1, more)
        compose.onNodeWithText(ALWAYS_BUY).performClick()
        assertEquals(true, pin)
        compose.onNodeWithText(USE_THIS).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, used)
    }

    @Test fun `detail of a product sold by weight steps in grams`() {
        val deli = page.choices[0].copy(product = Product("T", "Oven Roasted Turkey Breast", price = 4.5, weighted = true,
            priceQuantity = 100.0, minQty = 100, maxQty = 999, interval = 100))
        compose.setContent { ChooserContent(state.copy(detail = ChoiceDetail(deli, 300, false)), ChooserActions()) }
        compose.onNodeWithText("How much").assertExists()
        compose.onNodeWithContentDescription("Quantity 300 g").assertExists()
        compose.onNodeWithContentDescription("100 g more").assertExists()
        compose.onNodeWithText("About $13.50").assertExists()
        compose.onNodeWithText("$4.50/100 g").assertExists()
    }

    @Test fun `a cart that can't change shows the detail without the stepper and Use this off`() {
        compose.setContent { ChooserContent(state.copy(editable = false, detail = ChoiceDetail(page.choices[2], 2, false)), ChooserActions()) }
        compose.onNodeWithText("This cart can't be changed").assertIsNotEnabled()
        compose.onNodeWithText(ALWAYS_BUY).assertDoesNotExist()
        compose.onNodeWithContentDescription("Quantity 2").assertDoesNotExist()
    }
}
