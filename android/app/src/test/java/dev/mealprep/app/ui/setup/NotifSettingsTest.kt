package dev.mealprep.app.ui.setup

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.notify.Notifier
import dev.mealprep.app.notify.PlannedReminder
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotifSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val rate = PlannedReminder("rate-21", "RATE", "2026-10-14T09:00", "How was Tuesday's Chili?", "Tap to rate it.", "rating/21/2026-10-11")

    @Test fun `defaults - how was dinner and cart ready on, tonight and staples off`() {
        val changes = mutableListOf<NotifPrefs>()
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { NotifSettingsContent(NotifPrefs(), true, listOf(rate), { changes += it }) } }
        compose.onNodeWithText("Thaw reminder").assertDoesNotExist()                     // Sunday prep was retired
        compose.onNodeWithText("How was dinner?").assertIsOn()
        compose.onNodeWithText("Tonight's dinner").assertIsOff()
        compose.onNodeWithText("Cart ready to review").assertIsOn()
        compose.onNodeWithText("Tonight's dinner").performScrollTo().performClick()
        assertEquals(NotifPrefs(tonight = true), changes.single())
        compose.onNodeWithText(upcomingLine(rate)).performScrollTo().assertExists()
        assertTrue(upcomingLine(rate).startsWith("Wed Oct 14, "))
        assertTrue(upcomingLine(rate).endsWith(": How was Tuesday's Chili?"))
    }

    @Test fun `without the permission it says so and samples can't be shown`() {
        var allow = 0
        compose.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { NotifSettingsContent(NotifPrefs(), false, emptyList(), {}, onAllow = { allow++ }) } }
        compose.onNodeWithText("Notifications are off for Meal Prep, so none of these can show.").assertExists()
        compose.onNodeWithText("Allow notifications").performClick()
        assertEquals(1, allow)
        compose.onNodeWithText("Show a sample of each now").performScrollTo().assertIsNotEnabled()
    }

    @Test fun `samples use the next real reminder of a kind, else a made-up one, and all three kinds`() {
        val s = samples(listOf(rate), NotifPrefs(tonightAt = LocalTime.of(16, 30)))
        assertEquals(listOf("sample-rate", "sample-tonight", "sample-cart"), s.map { it.tag })
        val r = s[0]
        assertEquals("How was Tuesday's Chili?", r.title); assertEquals("Sample. Tap to rate it.", r.text); assertEquals("rating/21/2026-10-11", r.nav)
        assertTrue(s[1].text.startsWith("Sample. ") && s[1].text.contains(time(LocalTime.of(16, 30))))
        assertTrue(s[1].text.contains("opening the recipe") && s[1].text.contains("off on this phone"))
        assertEquals(Notifier.CH_JOBS, s[2].channel); assertEquals("list", s[2].nav)
    }
}
