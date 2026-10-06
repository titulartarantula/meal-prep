package dev.mealprep.app.ui.card

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.RatingNote
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.fixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardScreenTest {
    @get:Rule val compose = createComposeRule()
    private val list get() = compose.onNode(hasScrollAction())
    private fun scrollTo(text: String) = list.performScrollToNode(hasText(text)).let { compose.onNodeWithText(text) }
    private fun scrollToDesc(d: String) = list.performScrollToNode(hasContentDescription(d)).let { compose.onNodeWithContentDescription(d) }
    private val card = Http.json.decodeFromString(CookCard.serializer(), fixture("cook_card.json"))

    @Test fun `card shows its sections, timers start the Clock app, print prints`() {
        var timer: Pair<String, Int>? = null; var printed = 0; var step: Int? = null
        compose.setContent { CardContent(CardState(card, loading = false), setOf(0), CardUiActions(
            onStep = { step = it }, onTimer = { _, s, m -> timer = s.text to m }, onPrint = { printed++ })) }
        compose.onNodeWithText("Chili").assertExists()
        compose.onNodeWithText("Tue Oct 13 · about 30 min").assertExists()
        compose.onNodeWithText("• last time: less salt").assertExists()
        compose.onNodeWithText("• Chop the cilantro").assertExists()
        compose.onNodeWithText("1. Brown the beef with the onions").assertIsOn()
        scrollTo("2. Add tomatoes and beans; simmer").performClick()
        assertEquals(1, step)
        scrollToDesc("Start a 20-minute timer").performClick()
        assertEquals("Add tomatoes and beans; simmer" to 20, timer)
        scrollTo("Print this card").performClick()
        assertEquals(1, printed)
    }

    @Test fun `sections in night order, empty ones left out`() {
        assertEquals(listOf("Last time", "From your kit", "On the night"), cardSections(card).map { it.first })
    }

    @Test fun `stale card says to write a new plan`() {
        compose.setContent { CardContent(CardState(card.copy(stale = true), loading = false), emptySet(), CardUiActions()) }
        compose.onNodeWithText("This card was written before the recipe moved or changed size — write a new prep plan to refresh it.").assertExists()
    }

    @Test fun `no card shows the reason and a refresh`() {
        var reloads = 0
        compose.setContent { CardContent(CardState(null, loading = false, message = CardViewModel.NO_CARD), emptySet(), CardUiActions(onReload = { reloads++ })) }
        compose.onNodeWithText(CardViewModel.NO_CARD).assertExists()
        compose.onNodeWithText("Refresh").performClick()
        assertEquals(1, reloads)
    }

    @Test fun `from the night on the card offers to rate the dinner`() {
        var rated: Int? = null
        compose.setContent { CardContent(CardState(card, loading = false), emptySet(), CardUiActions(onRate = { rated = it.entryId }),
            today = java.time.LocalDate.parse("2026-10-13")) }
        scrollTo("How was it? Rate this dinner").performClick()
        assertEquals(21, rated)
        assertEquals(false, canRate(card, java.time.LocalDate.parse("2026-10-12")))
    }

    @Test fun `notes rated since the card was written come first, this night's own do not`() {
        val r = RatingSummary(timesRated = 3, notes = listOf(
            RatingNote("too spicy for the kids", "2026-10-13", "2026-10-14T12:00:00+00:00"),     // this very night: not "last time"
            RatingNote("double the beans", "2026-10-06", "2026-10-12T08:00:00+00:00"),         // after the card was written
            RatingNote("less salt", "2026-09-29", "2026-09-30T08:00:00+00:00"),                // already on the card
        ))
        assertEquals(listOf("Oct 6: double the beans", "last time: less salt"), cardNotes(card, r))
        assertEquals(card.ratingNotes, cardNotes(card, null))
        assertEquals(card.ratingNotes, cardNotes(card.copy(generatedAt = null), r))
    }
}
