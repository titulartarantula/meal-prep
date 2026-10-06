package dev.mealprep.app.ui.share

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.core.Sources
import dev.mealprep.app.ui.camera.PagesState
import dev.mealprep.app.ui.common.NAME_NEEDED
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The scan confirm screen's Book / Other choice (0.7.1). */
@RunWith(RobolectricTestRunner::class)
class ShareContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `Other asks for a name with the household's names as chips, and Save waits for one`() {
        val dir = File("unused")
        var state by mutableStateOf(ShareState(ShareInput.Pages(dir), dir = dir, pages = PagesState(listOf(File(dir, "page01.jpg"))),
            others = listOf("Mum's recipes")))
        compose.setContent {
            // Like ShareViewModel.confirm: without a name it only marks the field.
            ShareContent(state, onConfirm = { if (!state.canConfirm) state = state.copy(nameMissing = true) }, onCancel = {}, onSetup = {},
                onKind = { state = state.copy(kind = it) }, onOtherName = { state = state.copy(otherName = it) },
                onNote = { state = state.copy(note = it) })
        }
        compose.onNodeWithText("Where is it from?").assertExists()
        compose.onNodeWithText("Save a cookbook recipe").assertExists()
        compose.onNode(hasText("Book") and hasSetTextAction()).assertExists()
        compose.onNodeWithText("Other").performScrollTo().performClick()
        compose.onNodeWithText("Save a recipe").assertExists()
        compose.onNode(hasText("Book") and hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(SAVE).performScrollTo().assertIsEnabled().performClick()   // enabled: a tap says what's missing
        compose.onNodeWithText(NAME_NEEDED).performScrollTo().assertExists()
        compose.onNodeWithText("Mum's recipes").performScrollTo().performClick()
        compose.onNodeWithText(SAVE).performScrollTo().assertIsEnabled()
        check(state.kind == Sources.OTHER && state.otherName == "Mum's recipes")
    }
}
