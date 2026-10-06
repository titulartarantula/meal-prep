package dev.mealprep.app.ui.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
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
        onBack: () -> Unit = {}, onRestore: (Int, File) -> Unit = { _, _ -> },
    ) = compose.setContent {
        CameraContent("Photograph the recipe, page by page", state, busy, null, mode, preview = { Box {} },
            onShoot = onShoot, onSystemCamera = {}, onPick = onPick, onAllowCamera = onAllow, onRetake = onRetake,
            onCancelRetake = {}, onRemove = onRemove, onMove = onMove, onDone = onDone, onBack = onBack, onRestore = onRestore)
    }

    @Test fun `page buttons are labelled for TalkBack and act on the right page`() {
        val calls = mutableListOf<String>()
        show(pages(2), onMove = { i, by -> calls += "move $i $by" }, onRemove = { calls += "remove $it" },
            onRetake = { calls += "retake $it" })
        compose.onNodeWithContentDescription("Move page 1 earlier").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move page 2 later").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move page 2 earlier").performClick()
        // Tapping the picture retakes it ("Page 1, double-tap to retake page 1").
        compose.onNodeWithContentDescription("Page 1")
            .assert(SemanticsMatcher("retake label") { it.config.getOrElseNullable(SemanticsActions.OnClick) { null }?.label == "Retake page 1" })
            .performClick()
        compose.onNodeWithContentDescription("Delete page 2").performClick()
        assertEquals(listOf("move 1 -1", "retake 0", "remove 1"), calls)
        compose.onNodeWithText("Done (2)").assertIsEnabled()
        compose.onNodeWithText("Cancel").assertDoesNotExist()                // the top bar's Back leaves
        compose.onNodeWithText("Add page").assertIsEnabled()
    }

    @Test fun `a deleted page can be put back with Undo`() {
        var state by mutableStateOf(pages(3))
        compose.setContent {
            CameraContent("Photograph the recipe, page by page", state, false, null, CameraMode.Ready, preview = { Box {} },
                onShoot = {}, onSystemCamera = {}, onPick = {}, onAllowCamera = {}, onRetake = {}, onCancelRetake = {},
                onRemove = { state = state.remove(it) }, onMove = { _, _ -> }, onDone = {}, onBack = {},
                onRestore = { i, f -> state = state.restore(i, f) })
        }
        compose.onNodeWithContentDescription("Delete page 2").performClick()
        compose.onNodeWithContentDescription("Page 3").assertDoesNotExist()
        compose.onNodeWithText("Deleted page 2").assertExists()
        assertEquals(listOf("p1", "p3"), state.pages.map { it.nameWithoutExtension })
        compose.onNodeWithText("Undo").performClick()
        assertEquals(listOf("p1", "p2", "p3"), state.pages.map { it.nameWithoutExtension })
    }

    @Test fun `without the camera permission the pictures don't retake`() {
        show(pages(1), mode = CameraMode.NoPermission)
        compose.onNodeWithContentDescription("Page 1").assertHasNoClickAction()
    }

    @Test fun `Back leaves the camera`() {
        var back = 0
        show(PagesState(), onBack = { back++ })
        compose.onNodeWithText("Photograph the recipe, page by page").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithContentDescription(dev.mealprep.app.ui.common.NAVIGATE_UP).performClick()
        assertEquals(1, back)
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
