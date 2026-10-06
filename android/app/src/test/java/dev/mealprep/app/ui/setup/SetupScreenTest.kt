package dev.mealprep.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `typing a token and saving calls back`() {
        var token = ""; var saved = false
        compose.setContent {
            SetupContent(SetupState(url = "http://192.168.1.101:8790"), onUrl = {}, onToken = { token = it },
                onSave = { saved = true }, onContinue = {})
        }
        compose.onNodeWithText("Token").performTextInput("abc")
        compose.onNodeWithText("Save & test").performClick()
        assertEquals("abc", token); assertTrue(saved)
    }

    @Test fun `connected state shows continue`() {
        compose.setContent { SetupContent(SetupState(result = "Connected.", ok = true), {}, {}, {}, {}) }
        compose.onNodeWithText("Connected.").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
    }

    @Test fun `the token can be shown and Done on the keyboard saves`() {
        var saved = 0
        compose.setContent { SetupContent(SetupState(token = "secret"), {}, {}, { saved++ }, {}) }
        // What the field shows (its input text is "secret" either way).
        val shown = { compose.onNodeWithText("Token").fetchSemanticsNode().config[SemanticsProperties.EditableText].text }
        assertEquals("••••••", shown())
        compose.onNodeWithContentDescription("Show token").performClick()
        assertEquals("secret", shown())
        compose.onNodeWithContentDescription("Hide token").assertExists()
        compose.onNodeWithText("Token").performImeAction()
        assertEquals(1, saved)
    }

    @Test fun `Settings start with what people change, the server is one line with Change`() {
        var open = false
        compose.setContent {
            SettingsContent(onDone = {}, extra = { Text("Notifications on this phone"); Text("Loblaws") }, server = {
                ServerSection("http://server:8790", configured = true, status = serverStatus(null), open = open, onOpen = { open = true }) {
                    Text("the form")
                }
            })
        }
        val top = { t: String -> compose.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        assertTrue(top("Notifications on this phone") < top("Loblaws"))
        assertTrue(top("Loblaws") < top("Meal-prep server"))
        compose.onNodeWithText("Connected ✓").assertExists()
        compose.onNodeWithText("the form").assertDoesNotExist()
        compose.onNodeWithText("Change").performClick()
        assertTrue(open)
        assertEquals("It rejected the token. Tap Change.", serverStatus(dev.mealprep.app.data.api.ApiError.Unauthorized))
    }

    @Test fun `settings have no Continue (they have Done)`() {
        compose.setContent { SetupContent(SetupState(result = "Connected.", ok = true), {}, {}, {}, {}, showContinue = false) }
        compose.onNodeWithText("Connected.").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertDoesNotExist()
    }
}
