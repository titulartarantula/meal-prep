package dev.mealprep.app.ui.exchange

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.ui.library.LibraryMenu
import dev.mealprep.app.ui.library.RecipeContent
import dev.mealprep.app.ui.library.RecipeState
import dev.mealprep.app.ui.library.libraryMenu
import dev.mealprep.app.ui.library.rowDetail
import dev.mealprep.app.ui.library.sourceLinkLabel
import dev.mealprep.app.ui.library.spokenRowDetail
import dev.mealprep.app.ui.library.timesLine
import dev.mealprep.app.ui.theme.MealPrepTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ExportTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val env = TestEnv()
    private val vms = mutableListOf<ExportViewModel>()
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    @After fun tearDown() {
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        env.close()
    }

    private val written = mutableListOf<Pair<File, Uri>>()
    private fun vm(recipeId: Int? = null, write: (File, Uri) -> Unit = { f, u -> written += f to u }) =
        ExportViewModel(env.repo, tmp.newFolder(), recipeId, write).also { vms += it }

    private fun library() = env.on("GET", "/recipes/export", body = """{"@graph":[]}""",
        headers = mapOf("Content-Disposition" to "attachment; filename=\"meal-prep-recipes-2026-10-07.json\""))

    @Test fun `save makes the file, opens the picker once, then writes into the chosen document`() = runBlocking {
        library()
        val vm = vm()
        vm.start(ExportAction.SAVE)
        val s = vm.state.await { it.file != null }
        assertEquals(ExportAction.SAVE, s.pending); assertEquals("meal-prep-recipes-2026-10-07.json", s.file!!.name)
        vm.handled()
        assertNull(vm.state.value.pending)
        val doc = Uri.parse("content://com.example.documents/document/9")
        vm.saveTo(doc)
        assertEquals("Saved meal-prep-recipes-2026-10-07.json.", vm.state.await { it.saved != null }.saved)
        assertEquals(s.file!!.file to doc, written.single())
        vm.saveTo(null)                                                  // backed out of the picker: nothing happens
        assertEquals(1, written.size)
    }

    @Test fun `send after save reuses the file, no second download`() = runBlocking {
        library()
        val vm = vm()
        vm.start(ExportAction.SAVE); vm.state.await { it.file != null }; vm.handled()
        vm.start(ExportAction.SEND)
        assertEquals(ExportAction.SEND, vm.state.value.pending)
        assertEquals(1, env.count("GET", "/recipes/export"))
        vm.reset()                                                        // reopened: a fresh file next time
        vm.start(ExportAction.SEND); vm.state.await { it.file != null }
        assertEquals(2, env.count("GET", "/recipes/export"))
    }

    @Test fun `one recipe asks for that recipe`() = runBlocking {
        env.on("GET", "/recipes/4/export", body = "{}", headers = mapOf("Content-Disposition" to "attachment; filename=\"test-soup.recipe.json\""))
        val vm = vm(recipeId = 4)
        vm.start(ExportAction.SEND)
        assertEquals("test-soup.recipe.json", vm.state.await { it.file != null }.file!!.name)
    }

    @Test fun `offline says so and Try again repeats the last action`() = runBlocking {
        env.offline = true
        val vm = vm()
        vm.start(ExportAction.SEND)
        assertEquals(ExchangeText.OFFLINE_EXPORT, vm.state.await { it.error != null }.error)
        env.offline = false; library()
        vm.retry()
        assertEquals(ExportAction.SEND, vm.state.await { it.file != null }.pending)
    }

    @Test fun `a document that can't be written says so`() = runBlocking {
        library()
        val vm = vm(write = { _, _ -> throw java.io.IOException("disk full") })
        vm.start(ExportAction.SAVE); vm.state.await { it.file != null }; vm.handled()
        vm.saveTo(Uri.parse("content://x/y"))
        assertEquals(ExportViewModel.SAVE_FAILED, vm.state.await { it.error != null }.error)
    }

    private fun dialog(state: ExportState, all: Boolean = true, onSave: () -> Unit = {}) = compose.setContent {
        MealPrepTheme(dark = false) { ExportDialog(all, state, onSave, {}, {}, {}) }
    }

    @Test fun `the dialog offers Save and Send as 48 dp buttons`() {
        var saved = 0
        dialog(ExportState(), onSave = { saved++ })
        compose.onNodeWithText(EXPORT_ALL).assertExists()
        compose.onNodeWithText(SAVE_TO_FILE).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText(SEND).assertHeightIsAtLeast(48.dp)
        assertEquals(1, saved)
    }

    @Test fun `busy, saved and error are read out`() {
        var state by androidx.compose.runtime.mutableStateOf(ExportState(busy = true))
        compose.setContent { MealPrepTheme(dark = false) { ExportDialog(false, state, {}, {}, {}, {}) } }
        compose.onNodeWithText(SHARE_ONE).assertExists()
        compose.onNodeWithText(GETTING_READY).assert(polite)
        compose.onNodeWithText(SAVE_TO_FILE).assertIsNotEnabled()
        state = ExportState(saved = "Saved test-soup.recipe.json.")
        compose.onNodeWithText("Saved test-soup.recipe.json.").assert(polite)
        compose.onNodeWithText("Done").assertExists()
        state = ExportState(error = ExchangeText.OFFLINE_EXPORT)
        compose.onNodeWithText(ExchangeText.OFFLINE_EXPORT).assert(polite)
        compose.onNodeWithText("Try again").assertExists()
    }

    @Config(fontScale = 2f)
    @Test fun `at 200 percent text the dialog scrolls to every button`() {
        dialog(ExportState(error = ExchangeText.OFFLINE_EXPORT))
        compose.onNodeWithText("Try again").performScrollTo().assertExists()
        compose.onNodeWithText(SEND).performScrollTo().assertExists()
    }

    @Test fun `the recipe screen has a Share button that opens the export`() {
        var opened: Int? = null
        compose.setContent {
            MealPrepTheme(dark = false) {
                RecipeContent(RecipeState(recipe = Recipe(4, "Test Soup", "import"), loading = false), { _, _ -> }, {}, {}, {},
                    exportHost = { id, _ -> opened = id })
            }
        }
        compose.onNodeWithContentDescription(SHARE_ONE).assertTouchHeightIsEqualTo(48.dp).performClick()
        compose.waitForIdle()
        assertEquals(4, opened)
    }

    @Test fun `imported ratings are named on the recipe and the row`() {
        val r = Recipe(4, "Test Soup", "import", sourceKind = "other", sourceTitle = "Invented Kitchen",
            ratings = RatingSummary(timesCooked = 3, timesRated = 3, avgFamily = 4.0, importedRatings = 2))
        assertEquals("★ 4 · incl. 2 from another library · Invented Kitchen", rowDetail(r))
        assertTrue(spokenRowDetail(r).contains("including 2 from another library"))
        assertEquals("★ 4 · Invented Kitchen", rowDetail(r.copy(ratings = r.ratings.copy(importedRatings = 0))))
        compose.setContent {
            MealPrepTheme(dark = false) { RecipeContent(RecipeState(recipe = r, loading = false), { _, _ -> }, {}, {}, {}, exportHost = { _, _ -> }) }
        }
        compose.onNodeWithText("Ratings incl. 2 from another library").assertExists()
    }

    @Test fun `the link button says where it goes`() {
        val nyt = Recipe(1, "Chili", "nyt", sourceUrl = "https://cooking.nytimes.com/recipes/1-chili", sourceKind = "nyt")
        assertEquals("Open on NYT Cooking", sourceLinkLabel(nyt))
        assertEquals("Open the recipe's web page", sourceLinkLabel(Recipe(2, "Soup", "import", sourceUrl = "https://recipes.example.org/soup")))
        assertNull(sourceLinkLabel(Recipe(3, "Soup", "import", sourceUrl = "javascript:alert(1)")))
        assertNull(sourceLinkLabel(Recipe(4, "Soup", "photo")))
    }

    @Test fun `times and yield from an imported recipe`() {
        assertEquals("Prep 10 min · Cook 1 h 30 min · Makes 24 cookies",
            timesLine(Recipe(1, "Cookies", "import", prepMinutes = 10, cookMinutes = 90, yieldText = "Makes 24 cookies")))
        assertEquals("Total 2 h", timesLine(Recipe(1, "Stew", "import", totalMinutes = 120, servings = 4, yieldText = "4")))
        assertNull(timesLine(Recipe(1, "Plain", "nyt")))
    }

    @Test fun `the Recipes tab menu has Import and Export before the main items`() {
        val m = libraryMenu(listOf("Staples" to "s", "Settings" to "t"))
        assertEquals(listOf(LibraryMenu.IMPORT, LibraryMenu.EXPORT_ALL, "s", "t"), m.map { it.second })
        assertEquals(listOf("Import recipes from a file", "Export all recipes (a backup file)"), m.take(2).map { it.first })
    }
}
