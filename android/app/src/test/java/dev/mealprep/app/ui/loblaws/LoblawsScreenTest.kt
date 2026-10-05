package dev.mealprep.app.ui.loblaws

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mealprep.app.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoblawsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `blocked offers try again and copy`() {
        var retried = 0; var copied = 0
        compose.setContent { HandoffBanner(HandoffStep.BLOCKED, { retried++ }, { copied++ }, {}) }
        compose.onNodeWithText(bannerText(HandoffStep.BLOCKED)).assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Copy cart ID").performClick()
        assertEquals(1 to 1, retried to copied)
    }

    @Test fun `while working there is nothing to retry yet`() {
        compose.setContent { HandoffBanner(HandoffStep.INJECTING, {}, {}, {}) }
        compose.onNodeWithText("Putting your cart into Loblaws…").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Done").assertExists()
    }

    @Test fun `ready tells her to sign in`() {
        compose.setContent { HandoffBanner(HandoffStep.READY, {}, {}, {}) }
        compose.onNodeWithText(bannerText(HandoffStep.READY)).assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test fun `every failure state says something different from success`() {
        val ok = bannerText(HandoffStep.READY)
        listOf(HandoffStep.BLOCKED, HandoffStep.FAILED, HandoffStep.UNREACHABLE).forEach { assert(bannerText(it) != ok) }
    }

    @Test fun `a bad cart id shows a message, not a crash`() {
        var done = 0
        compose.setContent { LoblawsScreen("not-a-cart", Settings(), onDone = { done++ }) }
        compose.onNodeWithText("That isn't a Loblaws cart. Open the cart again from the week and tap Open in Loblaws.").assertExists()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, done)
    }

    @Test fun `settings switches`() {
        var signedOut: Boolean? = null
        compose.setContent { LoblawsSettingsContent(Settings(), onSignedOut = { signedOut = it }, onHideMarker = {}) }
        compose.onNodeWithText("Sign out before loading the cart (recommended)").assertExists()
        compose.onNodeWithText("Hide in-app browser marker (if Loblaws blocks the page)").assertExists()
        assertEquals(true, Settings().loblawsSignedOutStart)   // signed-out start is the default (only confirmed merge path)
        assertEquals(null, signedOut)
    }
}
