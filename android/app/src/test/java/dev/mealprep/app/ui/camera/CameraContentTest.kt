package dev.mealprep.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraContentTest {
    @get:Rule val compose = createComposeRule()

    private fun pages(n: Int) = (1..n).fold(PagesState()) { s, i -> s.add(File("/nonexistent/p$i.jpg")) }

    private fun show(
        state: PagesState, mode: CameraMode = CameraMode.Ready, busy: Boolean = false,
        onMove: (Int, Int) -> Unit = { _, _ -> }, onRemove: (Int) -> Unit = {}, onRetake: (Int) -> Unit = {},
        onDone: () -> Unit = {}, onShoot: () -> Unit = {}, onPick: () -> Unit = {}, onAllow: () -> Unit = {},
    ) = compose.setContent {
        CameraContent("Photograph the recipe, page by page", state, busy, null, mode, preview = { Box {} },
            onShoot = onShoot, onSystemCamera = {}, onPick = onPick, onAllowCamera = onAllow, onRetake = onRetake,
            onCancelRetake = {}, onRemove = onRemove, onMove = onMove, onDone = onDone, onCancel = {})
    }

    @Test fun `page buttons are labelled for TalkBack and act on the right page`() {
        val calls = mutableListOf<String>()
        show(pages(2), onMove = { i, by -> calls += "move $i $by" }, onRemove = { calls += "remove $it" },
            onRetake = { calls += "retake $it" })
        compose.onNodeWithContentDescription("Move page 1 earlier").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move page 2 later").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move page 2 earlier").performClick()
        compose.onNodeWithContentDescription("Retake page 1").performClick()
        compose.onNodeWithContentDescription("Delete page 2").performClick()
        assertEquals(listOf("move 1 -1", "retake 0", "remove 1"), calls)
        compose.onNodeWithContentDescription("Page 1").assertExists()       // the thumbnail itself
        compose.onNodeWithText("Done (2)").assertIsEnabled()
        compose.onNodeWithText("Add page").assertIsEnabled()
    }

    @Test fun `at ten pages only Done is left`() {
        show(pages(10))
        compose.onNodeWithText("Add page").assertIsNotEnabled()
        compose.onNodeWithText("From Photos").assertIsNotEnabled()
        compose.onNodeWithText("That's the most a recipe can have (10 pages).").assertExists()
        compose.onNodeWithText("Done (10)").assertIsEnabled()
    }

    @Test fun `without the camera permission photos can still be chosen`() {
        var allow = 0; var pick = 0
        show(PagesState(), mode = CameraMode.NoPermission, onAllow = { allow++ }, onPick = { pick++ })
        compose.onNodeWithText("Snap page").assertIsNotEnabled()
        compose.onNodeWithText("Done (0)").assertIsNotEnabled()
        compose.onNodeWithText("Allow camera").performClick()
        compose.onNodeWithText("From Photos").performClick()
        assertEquals(1 to 1, allow to pick)
    }

    @Test fun `camera that can't start offers the camera app`() {
        show(PagesState(), mode = CameraMode.Unavailable)
        compose.onNodeWithText("Use the camera app").assertIsEnabled()
        compose.onNodeWithText("Snap page").assertIsNotEnabled()
    }
}
