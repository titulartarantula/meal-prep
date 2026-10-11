package dev.mealprep.app.ui.library

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.fixture
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EditRecipeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-10-07")
    private val crepes = Http.json.decodeFromString(Recipe.serializer(), fixture("recipe_11.json")).copy(revision = "rev1")

    @After fun tearDown() = env.close()

    @Test fun `the editor shows the lines as written, grouped like the recipe screen, without the attached line`() {
        val d = RecipeDraft.from(crepes.copy(ingredients = crepes.ingredients.map { if (it.name == "lemon") it.copy(raw = "1/2  lemon") else it }))
        assertEquals("Crêpe batter:\n1 cup flour\n2 eggs\n1/2 lemon", d.ingredients)      // not "½": unchanged lines go back as they were
        assertEquals("Crêpe batter: whisk.\nCook the crêpes.\nSqueeze lemon over.", d.steps)
        assertEquals("4", d.servings)
        assertEquals("Crêpes with Lemon", d.title)
    }

    @Test fun `only what changed is sent, with the revision it began from`() {
        val d = RecipeDraft.from(crepes)
        assertNull(d.changes(crepes))
        assertNull(d.copy(title = " Crêpes  with Lemon ", ingredients = d.ingredients + "\n\n  ", steps = "  " + d.steps).changes(crepes))
        assertEquals("""{"title":"Crêpes with Lemons","expected_revision":"rev1"}""",
            d.copy(title = "Crêpes with  Lemons").changes(crepes).toString())
        val more = d.copy(servings = "", ingredients = d.ingredients.replace("2 eggs", "3 eggs"), notes = "Rest the batter.",
            prepMinutes = "15").changes(crepes)!!
        assertEquals("""{"servings":null,"ingredients":["Crêpe batter:","1 cup flour","3 eggs","1 lemon"],"notes":"Rest the batter.",""" +
            """"prep_minutes":15,"expected_revision":"rev1"}""", more.toString())
        assertEquals("""{"steps":["Whisk.","Cook."]}""", d.copy(steps = "Whisk.\n\nCook.").changes(crepes.copy(revision = null)).toString())
    }

    @Test fun `problems name the field`() {
        val d = RecipeDraft.from(crepes)
        assertTrue(d.problems().isEmpty())
        assertEquals(setOf(RecipeDraft.TITLE, RecipeDraft.SERVINGS, RecipeDraft.PREP, RecipeDraft.TOTAL),
            d.copy(title = "  ", servings = "0", prepMinutes = "-5", totalMinutes = "lots").problems().keys)
        assertTrue(d.copy(servings = " 12 ", cookMinutes = "").problems().isEmpty())
    }

    private fun vm(): RecipeViewModel {
        env.on("GET", "/recipes/11", body = fixture("recipe_11.json"))
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        return RecipeViewModel(env.repo, 11, today = { today })
    }

    @Test fun `saving sends the changes, re-reads the recipe and re-plans reminders`() = runTest {
        var changed = 0
        env.on("GET", "/recipes/11", body = fixture("recipe_11.json"))
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        val vm = RecipeViewModel(env.repo, 11, today = { today }, afterChange = { changed++ })
        val r = vm.state.await { it.recipe != null }.recipe!!
        val renamed = fixture("recipe_11.json").replace("Crêpes with Lemon", "Lemon Crêpes")
        env.on("PATCH", "/recipes/11", body = renamed)
        env.on("GET", "/recipes/11", body = renamed)
        var saved: Boolean? = null
        vm.saveEdit(RecipeDraft.from(r).copy(title = "Lemon Crêpes").changes(r)!!) { saved = it }
        vm.state.await { it.recipe?.title == "Lemon Crêpes" && !it.savingEdit }
        assertEquals(true, saved)
        assertEquals("""{"title":"Lemon Crêpes"}""", env.bodies("PATCH", "/recipes/11").single())
        assertEquals(1, changed)
        assertEquals(2, env.count("GET", "/recipes/11"))                              // the history comes with the re-read
    }

    @Test fun `off the home network or changed on another phone, the editor says so`() = runTest {
        val vm = vm()
        val r = vm.state.await { it.recipe != null }.recipe!!
        val body = RecipeDraft.from(r).copy(title = "X").changes(r)!!
        env.on("PATCH", "/recipes/11", code = 409, body = """{"detail":"This recipe was changed (on another phone?) since you started editing it."}""")
        var saved: Boolean? = null
        vm.saveEdit(body) { saved = it }
        val conflict = vm.state.await { it.editError != null }
        assertTrue(conflict.editError!!.startsWith("This recipe was changed on another phone"))
        assertEquals(false, saved)
        assertEquals("Crêpes with Lemon", conflict.recipe!!.title)
        env.offline = true
        vm.saveEdit(body)
        assertEquals("Saving changes needs the home network (or WireGuard). Your changes are still here.",
            vm.state.await { it.editError?.startsWith("Saving") == true }.editError)
        vm.editOpened()
        assertNull(vm.state.value.editError)
    }

    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))

    @Test fun `Edit in the top bar opens the editor, Save sends the changes and closes it`() {
        var sent: JsonObject? = null
        var opened = 0
        compose.setContent {
            RecipeContent(RecipeState(crepes, loading = false), onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {},
                today = today, onEditOpened = { opened++ }, onSaveEdit = { b, done -> sent = b; done(true) })
        }
        compose.onNodeWithContentDescription(EDIT_RECIPE).assertTouchHeightIsEqualTo(48.dp).performClick()
        assertEquals(1, opened)
        compose.onNodeWithText(EDIT_RECIPE).assertExists()
        compose.onNodeWithText(INGREDIENTS_HELP).assertExists()
        field("Title").performTextReplacement("Lemon Crêpes")
        field("Steps").performScrollTo().performTextReplacement("Whisk.\nCook.")
        compose.onNodeWithText("Save changes").performClick()
        assertEquals("""{"title":"Lemon Crêpes","steps":["Whisk.","Cook."],"expected_revision":"rev1"}""", sent.toString())
        compose.onNodeWithText(EDIT_RECIPE).assertDoesNotExist()
        compose.onNodeWithText("Add to a week").assertExists()
    }

    @Test fun `a blank title is named under the field and nothing is sent`() {
        var sent = 0
        compose.setContent {
            RecipeContent(RecipeState(crepes, loading = false), onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {},
                today = today, onSaveEdit = { _, _ -> sent++ })
        }
        compose.onNodeWithContentDescription(EDIT_RECIPE).performClick()
        field("Title").performTextReplacement("  ")
        compose.onNodeWithText("Save changes").performClick()
        compose.onNodeWithText(TITLE_NEEDED).assertExists()
        assertEquals(0, sent)
    }

    @Test fun `Save with nothing changed just closes, a failed save keeps the editor and its text`() {
        var calls = 0
        compose.setContent {
            RecipeContent(RecipeState(crepes, loading = false, editError = "Saving changes needs the home network (or WireGuard). Your changes are still here."),
                onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {}, today = today,
                onSaveEdit = { _, done -> calls++; done(false) })
        }
        compose.onNodeWithContentDescription(EDIT_RECIPE).performClick()
        compose.onNodeWithText("Save changes").performClick()
        assertEquals(0, calls)
        compose.onNodeWithText(EDIT_RECIPE).assertDoesNotExist()
        compose.onNodeWithContentDescription(EDIT_RECIPE).performClick()
        field("Title").performTextReplacement("Lemon Crêpes")
        compose.onNodeWithText("Save changes").performClick()
        assertEquals(1, calls)
        compose.onNodeWithText(EDIT_RECIPE).assertExists()
        compose.onNodeWithText("Saving changes needs the home network", substring = true).assertExists()
        field("Title").assertExists().assert(hasText("Lemon Crêpes"))
    }

    @Test fun `Back with changes asks before dropping them`() {
        compose.setContent {
            RecipeContent(RecipeState(crepes, loading = false), onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {}, today = today)
        }
        compose.onNodeWithContentDescription(EDIT_RECIPE).performClick()
        compose.onNodeWithContentDescription("Navigate up").performClick()           // nothing changed: closes at once
        compose.onNodeWithText(EDIT_RECIPE).assertDoesNotExist()
        compose.onNodeWithContentDescription(EDIT_RECIPE).performClick()
        field("Title").performTextReplacement("Lemon Crêpes")
        compose.onNodeWithContentDescription("Navigate up").performClick()
        compose.onNodeWithText(DISCARD_TITLE).assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        field("Title").assert(hasText("Lemon Crêpes"))
        compose.onNodeWithContentDescription("Navigate up").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText(EDIT_RECIPE).assertDoesNotExist()
        compose.onNodeWithText("Crêpes with Lemon").assertExists()
    }
}
