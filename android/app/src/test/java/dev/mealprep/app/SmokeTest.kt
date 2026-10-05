package dev.mealprep.app

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.mealprep.app.ui.theme.MealPrepTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `robolectric renders compose`() {
        compose.setContent { MealPrepTheme { Text("Meal Prep") } }
        compose.onNodeWithText("Meal Prep").assertIsDisplayed()
    }
}
