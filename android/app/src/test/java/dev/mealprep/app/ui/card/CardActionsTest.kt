package dev.mealprep.app.ui.card

import android.provider.AlarmClock
import dev.mealprep.app.data.api.CardStep
import dev.mealprep.app.data.api.CookCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardActionsTest {
    private val card = CookCard(21, 5, "Chili <hot>", "Tue", "2026-10-13", kit = listOf("2 diced onions"), dayOf = listOf("Chop the cilantro"),
        thaw = listOf("Cod: move to the fridge Wed night"), steps = listOf(CardStep("Simmer & stir <gently>", 20, 20)), totalMinutes = 30,
        ratingNotes = listOf("last time: less salt"))

    @Test fun `timer goes to the Clock app with seconds and a label`() {
        val i = CardActions.timerIntent(CardActions.timerLabel("Chili", card.steps[0]), 20)
        assertEquals(AlarmClock.ACTION_SET_TIMER, i.action)
        assertEquals(1200, i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        assertEquals("Chili: Simmer & stir <gently>", i.getStringExtra(AlarmClock.EXTRA_MESSAGE))
        assertTrue(i.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
    }

    @Test fun `timer label keeps the step short`() {
        val long = CardStep("Bring a large pot of well-salted water to a boil and cook the pasta until al dente", 10, 10)
        assertEquals("Chili: Bring a large pot of well-salted water t", CardActions.timerLabel("Chili", long))
    }

    @Test fun `printable card escapes text and has every section`() {
        val h = CardActions.html(card)
        assertTrue(h.contains("Chili &lt;hot&gt;")); assertFalse(h.contains("<hot>"))
        assertTrue(h.contains("Simmer &amp; stir &lt;gently&gt;"))
        assertTrue(h.contains("(timer 20 min)"))
        listOf("Last time", "Thaw", "From your kit", "On the night", "Steps", "last time: less salt", "2 diced onions").forEach {
            assertTrue(it, h.contains(it))
        }
    }

    @Test fun `empty sections are left off the print`() {
        val h = CardActions.html(card.copy(thaw = emptyList(), ratingNotes = emptyList()))
        assertFalse(h.contains("Thaw")); assertFalse(h.contains("Last time"))
    }

    @Test fun `subtitle has the night, date and time`() {
        assertEquals("Tue Oct 13 · about 30 min", CardActions.subtitle(card))
        assertEquals("Tue", CardActions.subtitle(card.copy(date = null, totalMinutes = 0)))
    }
}
