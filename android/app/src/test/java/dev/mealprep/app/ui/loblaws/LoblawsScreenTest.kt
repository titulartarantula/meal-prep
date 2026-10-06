package dev.mealprep.app.ui.loblaws

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mealprep.app.data.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LoblawsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `blocked offers try again and copy`() {
        var retried = 0; var copied = 0
        compose.setContent { HandoffBanner(HandoffStep.BLOCKED, null, { retried++ }, { copied++ }, {}, {}) }
        compose.onNodeWithText(bannerText(HandoffStep.BLOCKED)).assertExists()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Copy cart ID").performClick()
        assertEquals(1 to 1, retried to copied)
    }

    @Test fun `while working there is nothing to retry yet, and Close has no hint`() {
        var closed = 0
        compose.setContent { HandoffBanner(HandoffStep.INJECTING, null, {}, {}, {}, { closed++ }) }
        compose.onNodeWithText("Putting your cart into Loblaws…").assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Done").assertDoesNotExist()
        compose.onNodeWithText("Your cart stays in Loblaws.").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, closed)
    }

    @Test fun `ready tells her to sign in, and Close says the cart stays`() {
        compose.setContent { HandoffBanner(HandoffStep.READY, null, {}, {}, {}, {}) }
        compose.onNodeWithText(bannerText(HandoffStep.READY)).assertExists()
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Close").assertExists()
        compose.onNodeWithText("Your cart stays in Loblaws.").assertExists()
        compose.onNodeWithText("Details").assertDoesNotExist()
    }

    @Test fun `a failure shows collapsible details with copy`() {
        var copied = 0
        val details = "Step: FAILED (stopped while VERIFYING)\nPage: www.loblaws.ca"
        compose.setContent { HandoffBanner(HandoffStep.FAILED, details, {}, {}, { copied++ }, {}) }
        compose.onNodeWithText(bannerText(HandoffStep.FAILED)).assertExists()
        compose.onNodeWithText("You can open the cart again from the week.").assertExists()
        compose.onNodeWithText(details).assertDoesNotExist()          // collapsed until asked for
        compose.onNodeWithText("Copy details").assertDoesNotExist()
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText(details).assertExists()
        compose.onNodeWithText("Copy details").performClick()
        assertEquals(1, copied)
        compose.onNodeWithText("Hide details").performClick()
        compose.onNodeWithText(details).assertDoesNotExist()
    }

    @Test fun `close confirmation`() {
        var closed = 0; var stayed = 0
        compose.setContent { CloseConfirmDialog(onClose = { closed++ }, onStay = { stayed++ }) }
        compose.onNodeWithText("Still loading your cart. Close anyway?").assertExists()
        compose.onNodeWithText("Keep waiting").performClick()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1 to 1, closed to stayed)
    }

    @Test fun `every failure state says something different from success`() {
        val ok = bannerText(HandoffStep.READY)
        listOf(HandoffStep.BLOCKED, HandoffStep.FAILED, HandoffStep.UNREACHABLE).forEach { assert(bannerText(it) != ok) }
    }

    @Test fun `a bad cart id shows a message, not a crash`() {
        var done = 0
        compose.setContent { LoblawsScreen("not-a-cart", Settings(), onDone = { done++ }) }
        compose.onNodeWithText("That isn't a Loblaws cart. Open the cart again from the week and tap Open in Loblaws.").assertExists()
        compose.onNodeWithText("Close").performClick()
        assertEquals(1, done)
    }

    @Test fun `device trust is the only start switch, on by default`() {
        assertEquals(true, Settings().loblawsKeepDeviceTrust)
        var keep: Boolean? = null
        var prefs by mutableStateOf(Settings())
        compose.setContent { LoblawsSettingsContent(prefs, onHideMarker = {}, onKeepTrust = { keep = it; prefs = prefs.copy(loblawsKeepDeviceTrust = it) }) }
        compose.onNodeWithText("Sign out before loading the cart", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Hide in-app browser marker (if Loblaws blocks the page)").assertExists()
        compose.onNodeWithText(KEEP_TRUST_ON).assertExists()
        compose.onNodeWithText(KEEP_TRUST).performClick()
        assertEquals(false, keep)
        compose.onNodeWithText(KEEP_TRUST_OFF).assertExists()
    }
}
