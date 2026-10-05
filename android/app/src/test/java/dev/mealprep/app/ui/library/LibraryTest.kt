package dev.mealprep.app.ui.library

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.fixture
import dev.mealprep.app.ui.common.WeekOption
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-10-07")          // a Wednesday; the upcoming Sunday is Oct 11
    private val lib: List<Recipe> = Http.json.decodeFromString(ListSerializer(Recipe.serializer()), fixture("library.json"))

    @After fun tearDown() = env.close()

    @Test fun `search ignores case and accents, A-Z sorts on the phone`() {
        assertEquals(listOf(11), filterRecipes(lib, "CREPE", LibrarySort.NEWEST).map { it.id })
        assertEquals(listOf(12), filterRecipes(lib, "soup lentil", LibrarySort.NEWEST).map { it.id })
        assertEquals(listOf(10, 11, 12), filterRecipes(lib, " ", LibrarySort.AZ).map { it.id })
        assertEquals(listOf(12, 11, 10), filterRecipes(lib, "", LibrarySort.NEWEST).map { it.id })
    }

    @Test fun `planned weeks read like the week screen`() {
        assertEquals("On the plan: next week, week of Oct 25", plannedText(listOf("2026-10-25", "2026-10-11"), today))
        assertEquals("On the plan: this week", plannedText(listOf("2026-10-04"), today))
        assertNull(plannedText(emptyList(), today))
    }

    @Test fun `favourites asks the server, A-Z does not, offline shows the saved copy`() = runTest {
        env.on("GET", "/recipes", body = fixture("library.json"))
        val vm = LibraryViewModel(env.repo)
        vm.state.await { !it.loading && it.all.size == 3 }
        vm.sort(LibrarySort.FAVOURITES)
        vm.state.await { !it.loading && it.sort == LibrarySort.FAVOURITES }
        vm.sort(LibrarySort.AZ)
        assertEquals(listOf(10, 11, 12), vm.state.await { !it.loading && it.sort == LibrarySort.AZ }.shown.map { it.id })
        assertEquals(listOf("newest", "favourites", "newest"), env.requests.filter { it.url.encodedPath == "/recipes" }.map { it.url.queryParameter("sort") })
        vm.sort(LibrarySort.NEWEST)                                          // same server list: no new request
        assertEquals(3, env.count("GET", "/recipes"))
        env.offline = true
        vm.load()
        val s = vm.state.await { !it.loading && it.offlineSince != null }
        assertEquals(3, s.all.size)
        assertNull(s.error)
    }

    @Test fun `ingredients group an attached sub-recipe and drop the line it replaced`() {
        val lines = ingredientLines(lib.single { it.id == 11 })
        assertEquals(listOf("Crêpe batter:", "1 cup flour", "2 eggs", "1 lemon"), lines.map { it.text })
        assertEquals(listOf(true, false, false, false), lines.map { it.heading })
    }

    private fun recipeVm(): RecipeViewModel {
        env.on("GET", "/recipes/11", body = fixture("recipe_11.json"))
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        return RecipeViewModel(env.repo, 11, today = { today })
    }

    @Test fun `add to a week on a night`() = runTest {
        env.on("GET", "/weeks/2026-10-18", body = "[]")
        env.on("POST", "/weeks/2026-10-18/entries", body = """{"id":41,"week":"2026-10-18","recipe_id":11,"day":null}""")
        env.on("PATCH", "/plan/41", code = 204)
        val vm = recipeVm()
        vm.state.await { it.recipe != null }
        vm.addToWeek(LocalDate.parse("2026-10-20"), 2)                       // any day of that week
        val a = vm.state.await { it.added != null }.added!!
        assertEquals("Added to the week of Oct 18, on Tuesday.", a.text)
        assertEquals(LocalDate.parse("2026-10-18"), a.week)
        assertEquals("""{"recipe_id":11}""", env.bodies("POST", "/weeks/2026-10-18/entries").single())
        assertEquals("""{"day":2}""", env.bodies("PATCH", "/plan/41").single())
    }

    @Test fun `a recipe already in that week is not added twice`() = runTest {
        env.on("GET", "/weeks/2026-10-11", body = """[{"id":5,"week":"2026-10-11","recipe_id":11,"day":4,"title":"Crêpes with Lemon"}]""")
        val vm = recipeVm()
        vm.state.await { it.recipe != null }
        vm.addToWeek(LocalDate.parse("2026-10-11"), null)
        val a = vm.state.await { it.added != null }.added!!
        assertEquals("It's already in next week (Oct 11), on Thursday.", a.text)
        assertFalse(a.ok)
        assertEquals(0, env.count("POST", "/weeks/2026-10-11/entries"))
    }

    @Test fun `off the home network adding says it needs the home network`() = runTest {
        val vm = recipeVm()
        vm.state.await { it.recipe != null }
        env.offline = true
        vm.addToWeek(LocalDate.parse("2026-10-11"), null)
        assertEquals("Adding to a week needs the home network (or WireGuard).", vm.state.await { it.added != null }.added!!.text)
    }

    @Test fun `the detail shows the recipe and adds to the upcoming Sunday by default`() {
        val r = Http.json.decodeFromString(Recipe.serializer(), fixture("recipe_11.json"))
        var added: Pair<LocalDate, Int?>? = null
        val opts = listOf(WeekOption(LocalDate.parse("2026-10-04"), "This week (Oct 4)", null), WeekOption(LocalDate.parse("2026-10-11"), "Next week (Oct 11)", null))
        compose.setContent {
            RecipeContent(RecipeState(r.copy(plannedWeeks = listOf("2026-10-04")), loading = false, options = opts),
                onAdd = { w, d -> added = w to d }, onWeek = {}, onOpen = {}, onDismissAdded = {}, today = today)
        }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("• 2 eggs"))
        list.performScrollToNode(hasText("2. Cook the crêpes."))
        list.performScrollToNode(hasText("Week of Sep 27 · Wed"))
        compose.onNodeWithText("Add to a week").performClick()
        compose.onNodeWithText("This week (Oct 4)").performClick()                // already planned there
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.onNodeWithText("Next week (Oct 11)").performClick()
        compose.onNodeWithText("Fri").performClick()
        compose.onNodeWithText("Add").performClick()
        assertEquals(LocalDate.parse("2026-10-11") to 5, added)
    }

    @Test fun `library rows show ratings, planned weeks and a missing page`() {
        val withRef = lib.map { if (it.id == 11) it.copy(ingredients = it.ingredients.map { i -> i.copy(expanded = false) }) else it }
        var opened: Int? = null
        compose.setContent {
            LibraryContent(LibraryState(withRef, loading = false), {}, {}, onRecipe = { opened = it }, onRetry = {}, today = today)
        }
        compose.onNodeWithText("Family 5/5 · Company: yes · “more cinnamon”").assertExists()
        compose.onNodeWithText("On the plan: next week, week of Oct 25").assertExists()
        compose.onNodeWithText("Uses page 191: add a photo of it so its ingredients are on the list.").assertExists()
        compose.onNodeWithText("Apple Crumble").performClick()
        assertEquals(10, opened)
        compose.onNodeWithText("Not rated yet").assertExists()
    }
}
