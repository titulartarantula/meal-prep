package dev.mealprep.app.ui.exchange

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.ImportReport
import dev.mealprep.app.data.api.Progress
import dev.mealprep.app.fixture
import dev.mealprep.app.ui.theme.MealPrepTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val preview = Http.json.decodeFromString(ImportReport.serializer(), fixture("import_preview.json"))
    private val done = Http.json.decodeFromString(ImportReport.serializer(), fixture("import_job_done.json"))
    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val checkbox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
    private fun toggle(on: Boolean) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, if (on) ToggleableState.On else ToggleableState.Off)
    private val notToggleable = SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState)

    private fun show(state: ImportState, actions: ImportActions = ImportActions()) =
        compose.setContent { MealPrepTheme(dark = false) { ImportContent(state, actions) } }

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    @Test fun `the preview has a summary heading, sections and checkbox rows`() {
        val toggled = mutableListOf<String>()
        show(ImportState(name = "recipes.json", report = preview, ticks = ImportLogic.defaultTicks(preview)), ImportActions(onToggle = { toggled += it }))
        compose.onNodeWithText(IMPORT_TITLE).assert(heading)
        compose.onNodeWithText("recipes.json").assertExists()
        compose.onNodeWithText("2 new · 1 changed since exported · 2 already in Recipes · 1 couldn't be read").assert(heading)
        compose.onNodeWithText("New (2)").assert(heading)
        val soup = compose.onNode(hasText("Test Lentil Soup") and checkbox)
        soup.assert(toggle(true)).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("0:9f2c1a7e"), toggled)
        compose.onNode(hasText("Test Lentil Soup") and checkbox).assert(hasText("New · 9 ingredients", substring = true))
        scrollTo("Changed since it was exported (1)")
        compose.onNodeWithText("Changed since it was exported (1)").assert(heading)
        compose.onNode(hasText("Test Tomato Tart") and checkbox).assert(toggle(false)).assert(hasText("Replace with the file's version"))
        scrollTo("Test Pancakes")
        compose.onNode(hasText("Test Pancakes") and checkbox).assert(hasText("Add anyway"))
        scrollTo("Test Bean Chili")
        compose.onNode(hasText("Test Bean Chili")).assert(notToggleable).assert(hasText("Same as “Test Bean Chili” in Recipes"))
        scrollTo("Recipe 6 (no name)")
        compose.onNodeWithText("Couldn't read (1)").assert(heading)
        compose.onNode(hasText("Recipe 6 (no name)")).assert(notToggleable).assert(hasText("⚠ No recipe name"))
    }

    @Test fun `the docked button counts the ticks and is off at zero`() {
        var applied = 0
        show(ImportState(report = preview, ticks = ImportLogic.defaultTicks(preview)), ImportActions(onApply = { applied++ }))
        compose.onNodeWithText("Add 2 recipes").assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, applied)
        compose.onNodeWithText(AI_NOTE).assertExists()                   // the soup's lines go through the AI
    }

    @Test fun `nothing ticked disables the button`() {
        show(ImportState(report = preview, ticks = emptySet()))
        compose.onNodeWithText("Nothing selected").assertIsNotEnabled()
        compose.onNodeWithText(AI_NOTE).assertDoesNotExist()
    }

    @Test fun `reading, file errors and an apply error are read out`() {
        show(ImportState(name = "recipes.json", loading = true))
        compose.onNodeWithText("Reading recipes.json…").assert(polite)
    }

    @Test fun `a file error offers Try again and another file`() {
        var picked = 0
        show(ImportState(error = ExchangeText.NOT_JSON, canRetry = true), ImportActions(onPickAnother = { picked++ }))
        compose.onNodeWithText(ExchangeText.NOT_JSON).assert(polite)
        compose.onNodeWithText("Try again").assertExists()
        compose.onNodeWithText(CHOOSE_ANOTHER).performClick()
        assertEquals(1, picked)
        compose.onNodeWithText("Nothing selected").assertDoesNotExist()   // no button without a preview
    }

    @Test fun `apply error shows above the button`() {
        show(ImportState(report = preview, ticks = setOf("0:9f2c1a7e"), applyError = ExchangeText.OFFLINE_IMPORT))
        compose.onNodeWithText(ExchangeText.OFFLINE_IMPORT).assert(polite)
        compose.onNodeWithText("Add 1 recipe").assertIsEnabled()
    }

    @Test fun `while the job runs, progress is read out and you can leave`() {
        var left = 0
        show(ImportState(job = done.copy(status = "running", progress = Progress(1, 3))), ImportActions(onDone = { left++ }))
        compose.onNodeWithText("Adding 2 of 3…").assert(polite)
        compose.onNodeWithText(LEAVE_NOTE).assertExists()
        compose.onNodeWithText(SHOW_IN_RECIPES).performClick()
        assertEquals(1, left)
    }

    @Test fun `the result is a heading, read out, with the new recipes to open`() {
        val opened = mutableListOf<Int>()
        show(ImportState(job = done), ImportActions(onRecipe = { opened += it }))
        compose.onNodeWithText("Added 2 recipes. Updated 1 recipe. 1 was already in Recipes. 1 couldn't be added.").assert(heading).assert(polite)
        compose.onNodeWithText("Added (3)").assert(heading)
        compose.onNode(hasText("Test Lentil Soup")).assert(hasText("⚠ Ingredients tidied without AI (the AI didn't answer)")).performClick()
        assertEquals(listOf(31), opened)
        scrollTo("Couldn't be added (1)")
        compose.onNode(hasText("Recipe 6 (no name)")).assert(hasText("⚠ No recipe name"))
        compose.onNodeWithText(SHOW_IN_RECIPES).assertIsDisplayed()
    }

    @Config(fontScale = 2f)
    @Test fun `at 200 percent text the rows wrap and the button stays on screen`() {
        show(ImportState(report = preview, ticks = ImportLogic.defaultTicks(preview)))
        compose.onNodeWithText("Add 2 recipes").assertIsDisplayed()
        scrollTo("Recipe 6 (no name)")
        compose.onNode(hasText("Recipe 6 (no name)")).assertIsDisplayed()
        compose.onNodeWithText("Add 2 recipes").assertIsDisplayed()
    }

    @Test fun `a document being read shows the part and what to expect, read out`() {
        val reading = Http.json.decodeFromString(ImportReport.serializer(), fixture("import_read_running.json"))
        show(ImportState(name = "cookbook.pdf", loading = true, reading = reading))
        compose.onNodeWithText("Reading cookbook.pdf…").assert(polite)
        compose.onNodeWithText("Part 2 of 3").assert(polite)
        compose.onNodeWithText(READ_NOTE).assertExists()
    }

    @Test fun `select all and select none for a file with many recipes`() {
        val calls = mutableListOf<Boolean>()
        show(ImportState(report = preview, ticks = setOf("0:9f2c1a7e")), ImportActions(onSelectAll = { calls += it }))
        compose.onNodeWithText(SELECT_ALL).assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        compose.onNodeWithText(SELECT_NONE).assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        assertEquals(listOf(true, false), calls)
    }

    @Test fun `select all is off when all are ticked, select none when none are`() {
        show(ImportState(report = preview, ticks = ImportLogic.selectableKeys(preview)))
        compose.onNodeWithText(SELECT_ALL).assertIsNotEnabled()
        compose.onNodeWithText(SELECT_NONE).assertIsEnabled()
    }

    @Test fun `a file with few recipes has no select all`() {
        show(ImportState(report = preview.copy(items = preview.items.take(2)), ticks = emptySet()))
        compose.onNodeWithText(SELECT_ALL).assertDoesNotExist()
    }
}
