package dev.mealprep.app.ui.setup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    @Test fun `settings have no Continue (they have Done)`() {
        compose.setContent { SetupContent(SetupState(result = "Connected.", ok = true), {}, {}, {}, {}, showContinue = false) }
        compose.onNodeWithText("Connected.").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertDoesNotExist()
    }
}
