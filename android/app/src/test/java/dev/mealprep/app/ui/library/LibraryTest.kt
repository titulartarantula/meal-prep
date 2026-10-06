package dev.mealprep.app.ui.library

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.ui.home.ImportJob
import dev.mealprep.app.work.ImportWorker
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.BookHit
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.core.BookChoice
import dev.mealprep.app.core.BookSuggestion
import dev.mealprep.app.core.Books
import dev.mealprep.app.core.Sources
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextClearance
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

    @Test fun `the source filter narrows the list to one named other source`() {
        val mixed = lib.map { if (it.id == 10) it.copy(sourceKind = "other", sourceTitle = "Mum's recipes", sourceUrl = null, source = "manual") else it } +
            lib.single { it.id == 11 }.copy(id = 13, sourceKind = "other", sourceTitle = null)
        assertEquals(listOf(10), filterRecipes(mixed, "", LibrarySort.NEWEST, "other:mum's recipes").map { it.id })
        assertEquals(listOf(13), filterRecipes(mixed, "", LibrarySort.NEWEST, "other:").map { it.id })
        val labels = Sources.options(mixed).map { it.label }
        assertEquals(listOf("All sources", "Invented Pantry Book", "Mum's recipes", "Unknown book", "Other"), labels)
    }

    @Test fun `the source filter narrows the list, unknown books included`() {
        assertEquals(listOf(10), filterRecipes(lib, "", LibrarySort.NEWEST, "nyt").map { it.id })
        assertEquals(listOf(11), filterRecipes(lib, "", LibrarySort.NEWEST, "book:").map { it.id })
        assertEquals(listOf(12), filterRecipes(lib, "soup", LibrarySort.NEWEST, "book:invented pantry book").map { it.id })
        assertEquals(emptyList<Int>(), filterRecipes(lib, "crepe", LibrarySort.NEWEST, "nyt").map { it.id })
    }

    @Test fun `rows say where each recipe is from and the filter picks Unknown book`() {
        var state by mutableStateOf(LibraryState(lib, loading = false))
        compose.setContent {
            LibraryContent(state, {}, {}, onRecipe = {}, onRetry = {}, today = today, onSource = { state = state.copy(source = it) })
        }
        compose.onNodeWithText("Invented Pantry Book, p. 88").assertExists()
        compose.onNodeWithText("NYT Cooking").assertExists()
        compose.onNodeWithText("All sources").performClick()
        compose.onNodeWithText("Unknown book (1)").performClick()
        compose.onNodeWithText("Crêpes with Lemon").assertExists()
        compose.onNodeWithText("Apple Crumble").assertDoesNotExist()
    }

    @Test fun `edit source sends the book and page, offline says it needs the home network`() = runTest {
        env.on("PATCH", "/recipes/11", body = fixture("recipe_11.json").replace("\"source_title\": null", "\"source_title\": \"Invented Bakes\""))
        val vm = recipeVm()
        vm.state.await { it.recipe != null }
        env.on("GET", "/recipes/11", body = fixture("recipe_11.json").replace("\"source_title\": null", "\"source_title\": \"Invented Bakes\""))
        var saved: Boolean? = null
        vm.editSource(BookChoice(" Invented Bakes "), "", { saved = it })
        vm.state.await { it.recipe?.sourceTitle == "Invented Bakes" && !it.savingSource }
        assertEquals(true, saved)
        assertEquals("""{"source_kind":"book","source_title":"Invented Bakes","source_ref":null,"source_author":null,"source_isbn":null}""",
            env.bodies("PATCH", "/recipes/11").single())
        env.offline = true
        vm.editSource(BookChoice("X"), "1")
        assertEquals("Changing the source needs the home network (or WireGuard).", vm.state.await { it.sourceError != null }.sourceError)
    }

    @Test fun `the detail shows the source with Add book for an unknown one`() {
        val r = Http.json.decodeFromString(Recipe.serializer(), fixture("recipe_11.json"))
        var edited: Pair<BookChoice, String>? = null
        compose.setContent {
            RecipeContent(RecipeState(r, loading = false, books = listOf(BookSuggestion("Invented Bakes", mine = true))), onAdd = { _, _ -> },
                onWeek = {}, onOpen = {}, onDismissAdded = {}, today = today, onEditSource = { b, p, done -> edited = b to p; done(true) })
        }
        compose.onNodeWithText("From: Unknown book").assertExists()
        compose.onNodeWithText("Add book").performClick()
        compose.onNodeWithText("Invented Bakes").performClick()                   // the suggestion fills the field
        compose.onNodeWithText("Page (optional)").performTextInput("191")
        compose.onNodeWithText("Save").performClick()
        assertEquals("Invented Bakes" to "191", edited?.let { it.first.title to it.second })
        compose.onNodeWithText("Where is it from?").assertDoesNotExist()
    }

    @Test fun `edit source offers the book search and saves the picked book's author`() {
        val r = Http.json.decodeFromString(Recipe.serializer(), fixture("recipe_11.json"))
        var edited: BookChoice? = null
        val typed = mutableListOf<String>()
        var state by mutableStateOf(RecipeState(r, loading = false, books = listOf(BookSuggestion("Invented Bakes", mine = true))))
        compose.setContent {
            RecipeContent(state, onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {}, today = today,
                onEditSource = { b, _, done -> edited = b; done(true) }, onBookTyped = { typed += it })
        }
        compose.onNodeWithText("Add book").performClick()
        compose.onNodeWithText("Your books").assertExists()
        compose.onNode(hasText("Book") and hasSetTextAction()).performTextInput("Imag")
        assertEquals("Imag", typed.last())
        state = state.copy(found = Books.found(Http.json.decodeFromString(ListSerializer(BookHit.serializer()), fixture("books_search.json"))))
        compose.onNodeWithText("Book search").assertExists()
        compose.onNodeWithText("Your books").assertDoesNotExist()        // "Imag" isn't in Invented Bakes
        compose.onNodeWithContentDescription("by Ada Pepper, Basil Thyme, 1999").assertExists()
        compose.onNodeWithText("The Imaginary Larder: Suppers from an Invented Pantry").assertHasClickAction().performClick()
        compose.onNodeWithText("Book search").assertDoesNotExist()
        compose.onNodeWithText("Save").performClick()
        assertEquals(BookChoice("The Imaginary Larder", "Ada Pepper, Basil Thyme", "9780000000017", picked = true), edited)
    }

    @Test fun `the source line shows the author, and editing only the page keeps it`() = runTest {
        val withBook = fixture("recipe_11.json").replace("\"source_title\": null, \"source_ref\": null",
            "\"source_title\": \"Invented Bakes\", \"source_ref\": \"12\", \"source_author\": \"Ada Pepper\", \"source_isbn\": \"9780000000017\"")
        val r = Http.json.decodeFromString(Recipe.serializer(), withBook)
        var edited: Pair<BookChoice, String>? = null
        compose.setContent {
            RecipeContent(RecipeState(r, loading = false), onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {},
                today = today, onEditSource = { b, p, done -> edited = b to p; done(true) })
        }
        compose.onNodeWithText("From: Invented Bakes · Ada Pepper, p. 12").assertExists()
        compose.onNodeWithText("Edit source").performClick()
        compose.onNodeWithText("Page (optional)").performTextClearance()
        compose.onNodeWithText("Page (optional)").performTextInput("14")
        compose.onNodeWithText("Save").performClick()
        assertEquals(BookChoice("Invented Bakes", "Ada Pepper", "9780000000017", picked = true) to "14", edited)
        // and the view model sends them back as they are
        val vm = recipeVm()
        vm.state.await { it.recipe != null }
        env.on("GET", "/recipes/11", body = withBook)
        env.on("PATCH", "/recipes/11", body = withBook)
        vm.editSource(edited!!.first, "14")
        vm.state.await { !it.savingSource && env.count("PATCH", "/recipes/11") == 1 }
        assertEquals("""{"source_kind":"book","source_title":"Invented Bakes","source_ref":"14","source_author":"Ada Pepper","source_isbn":"9780000000017"}""",
            env.bodies("PATCH", "/recipes/11").single())
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

    @Test fun `paste dialog takes only NYT recipe links and offers the copied one`() {
        var saved: String? = null
        compose.setContent { PasteLinkDialog("https://cooking.nytimes.com/recipes/1015819-x", onSave = { saved = it }, onDismiss = {}) }
        compose.onNodeWithText("Save to Recipes").assertIsNotEnabled()
        compose.onNodeWithText("NYT Cooking link").performTextInput("https://example.com/soup")
        compose.onNodeWithText(NOT_NYT_LINK).assertExists()
        compose.onNodeWithText("Save to Recipes").assertIsNotEnabled()
        compose.onNodeWithText("Use cooking.nytimes.com/recipes/1015819-x").performClick()
        compose.onNodeWithText("Save to Recipes").performClick()
        assertEquals("https://cooking.nytimes.com/recipes/1015819-x", saved)
    }

    @Test fun `an NYT link on the clipboard is found, anything else is not`() {
        val cm = env.context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("x", "Try https://www.cooking.nytimes.com/recipes/1015819-x?smid=ck"))
        assertEquals("https://cooking.nytimes.com/recipes/1015819-x", clipboardNytLink(env.context))
        cm.setPrimaryClip(ClipData.newPlainText("x", "https://example.com/soup"))
        assertNull(clipboardNytLink(env.context))
    }

    @Test fun `a recipe saved in the background reloads the library`() = runTest {
        env.on("GET", "/recipes", body = fixture("library.json"))
        val jobs = MutableStateFlow(emptyList<ImportJob>())
        val vm = LibraryViewModel(env.repo, jobs = jobs)
        vm.state.await { !it.loading && it.all.size == 3 }
        val id = UUID.randomUUID()
        jobs.value = listOf(ImportJob(id, WorkInfo.State.SUCCEEDED, workDataOf(ImportWorker.OUT_RECIPE_ID to 12, ImportWorker.OUT_TITLE to "Soup")))
        vm.imports.await { it.size == 1 }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (env.count("GET", "/recipes") < 2) delay(10) } }
        vm.state.await { !it.loading }
        vm.dismissImport(id)
        vm.imports.await { it.isEmpty() }
        // the dismissal is saved in the background: let it finish before the test closes the database
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (!env.repo.isMarked("hide-import:$id")) delay(10) } }
    }

    @Test fun `an empty library says how to add a recipe`() {
        compose.setContent { LibraryContent(LibraryState(emptyList(), loading = false), {}, {}, onRecipe = {}, onRetry = {}, today = today) }
        compose.onNodeWithText(EMPTY_LIBRARY).assertExists()
    }

    @Test fun `library rows show ratings, planned weeks and a missing page`() {
        val withRef = lib.map { if (it.id == 11) it.copy(ingredients = it.ingredients.map { i -> i.copy(expanded = false) }) else it }
        var opened: Int? = null
        compose.setContent {
            LibraryContent(LibraryState(withRef, loading = false), {}, {}, onRecipe = { opened = it }, onRetry = {}, today = today)
        }
        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("Uses page 191: add a photo of it so its ingredients are on the list."))
        list.performScrollToNode(hasText("Family 5/5 · Company: yes · “more cinnamon”"))
        list.performScrollToNode(hasText("On the plan: next week, week of Oct 25"))
        compose.onNodeWithText("Apple Crumble").performClick()
        assertEquals(10, opened)
        list.performScrollToNode(hasText("Not rated yet"))
    }

    @Test fun `good for company shows only recipes whose verdict is yes`() {
        val a = Recipe(1, "Alpha", "nyt", ratings = dev.mealprep.app.data.api.RatingSummary(timesRated = 1, company = "yes"))
        val b = Recipe(2, "Beta", "nyt", ratings = dev.mealprep.app.data.api.RatingSummary(timesRated = 1, company = "maybe"))
        val c = Recipe(3, "Gamma", "nyt")
        assertEquals(listOf(a, b, c), filterRecipes(listOf(a, b, c), "", LibrarySort.NEWEST))
        assertEquals(listOf(a), filterRecipes(listOf(a, b, c), "", LibrarySort.NEWEST, company = true))
        var on: Boolean? = null
        compose.setContent { LibraryContent(LibraryState(all = listOf(b, c), company = true, loading = false), {}, {}, {}, {}, today, onCompany = { on = it }) }
        compose.onNodeWithText(NO_COMPANY).assertExists()
        compose.onNodeWithText("Good for company").performClick()
        assertEquals(false, on)
    }

    // --- other sources (0.7.1) ---

    @Test fun `edit source can switch to Other, offers the household's names and needs a name`() {
        val r = Http.json.decodeFromString(Recipe.serializer(), fixture("recipe_11.json"))
        var other: Pair<String, String>? = null
        var book: BookChoice? = null
        val typed = mutableListOf<String>()
        compose.setContent {
            RecipeContent(RecipeState(r, loading = false, books = listOf(BookSuggestion("Invented Bakes", mine = true)),
                others = listOf("Allotment Club", "Mum's recipes")), onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {},
                today = today, onEditSource = { b, _, done -> book = b; done(true) }, onBookTyped = { typed += it },
                onEditOther = { n, note, done -> other = n to note; done(true) })
        }
        compose.onNodeWithText("Add book").performClick()                      // the Unknown book backfill still starts on Book
        compose.onNodeWithText("Your books").assertExists()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("Your books").assertDoesNotExist()
        compose.onNodeWithText("Used before").assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()                    // a name is needed
        compose.onNode(hasText("Name") and hasSetTextAction()).performTextInput("mum")
        compose.onNodeWithText("Allotment Club").assertDoesNotExist()          // chips narrow to what is typed
        compose.onNodeWithText("Mum's recipes").performClick()
        compose.onNodeWithText("Note (optional)").performTextInput("blue binder")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()
        assertEquals("Mum's recipes" to "blue binder", other)
        assertNull(book)
        assertEquals(emptyList<String>(), typed)                                // no book search for Other
        compose.onNodeWithText("Where is it from?").assertDoesNotExist()
    }

    @Test fun `an other source shows its name and opens on Other, and Book keeps the book fields apart`() {
        val mums = fixture("recipe_11.json").replace("\"source_kind\": \"book\", \"source_title\": null, \"source_ref\": null",
            "\"source_kind\": \"other\", \"source_title\": \"Mum's recipes\", \"source_ref\": \"blue binder\"")
        val r = Http.json.decodeFromString(Recipe.serializer(), mums)
        var book: Pair<BookChoice, String>? = null
        compose.setContent {
            RecipeContent(RecipeState(r, loading = false), onAdd = { _, _ -> }, onWeek = {}, onOpen = {}, onDismissAdded = {},
                today = today, onEditSource = { b, p, done -> book = b to p; done(true) })
        }
        compose.onNodeWithText("From: Mum's recipes, blue binder").assertExists()
        compose.onNodeWithText("Edit source").performClick()
        compose.onNode(hasText("Mum's recipes") and hasSetTextAction()).assertExists()
        compose.onNode(hasText("blue binder") and hasSetTextAction()).assertExists()
        compose.onNodeWithText("Book").performClick()                         // the chip: the book fields start empty
        compose.onNodeWithText("Page (optional)").performTextInput("12")
        compose.onNodeWithText("Save").performClick()
        assertEquals(BookChoice("") to "12", book)                             // empty = Unknown book
    }

    @Test fun `edit other sends the name in the household's spelling, no author or ISBN, and no book search`() = runTest {
        val mums = fixture("recipe_11.json").replace("\"source_kind\": \"book\", \"source_title\": null",
            "\"source_kind\": \"other\", \"source_title\": \"Mum's recipes\"")
        env.on("GET", "/recipes/sources", body = """[{"key":"book:Invented Bakes","kind":"book","title":"Invented Bakes",
            "label":"Invented Bakes","count":1,"author":"Ada Pepper"},{"key":"other:Mum's recipes","kind":"other","title":"Mum's recipes",
            "label":"Mum's recipes","count":2}]""")
        env.on("PATCH", "/recipes/11", body = mums)
        val vm = recipeVm()
        vm.state.await { it.recipe != null }
        vm.loadBooks()
        assertEquals(listOf("Mum's recipes"), vm.state.await { it.others.isNotEmpty() }.others)
        env.on("GET", "/recipes/11", body = mums)
        var saved: Boolean? = null
        vm.editOther(" MUM'S  recipes ", " blue binder ", { saved = it })
        val s = vm.state.await { it.recipe?.sourceKind == "other" && !it.savingSource }
        assertEquals(true, saved)
        assertEquals("Mum's recipes", s.recipe?.sourceTitle)
        assertEquals("""{"source_kind":"other","source_title":"Mum's recipes","source_ref":"blue binder","source_author":null,"source_isbn":null}""",
            env.bodies("PATCH", "/recipes/11").single())
        assertEquals(0, env.count("GET", "/books/search"))
        env.offline = true
        vm.editOther("Gran", "")
        assertEquals("Changing the source needs the home network (or WireGuard).", vm.state.await { it.sourceError != null }.sourceError)
    }
}
