package dev.mealprep.app.data.api

import dev.mealprep.app.fixture
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var api: MealPrepApi

    @Before fun setUp() { server.start(); api = Http.api(server.url("/").toString(), Http.client({ "tok" })) }
    @After fun tearDown() { server.close() }

    private fun reply(body: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    private fun noContent() = server.enqueue(MockResponse.Builder().code(204).build())
    private fun sentBody() = server.takeRequest().body!!.utf8()

    @Test fun `sends the bearer token and parses captured weeks`() = runTest {
        reply(fixture("weeks.json"))
        val weeks = api.weeks("2026-10-04", 8)
        assertEquals(8, weeks.size)
        assertEquals("2026-10-04", weeks[0].week)
        assertTrue(weeks.all { LocalDate.parse(it.week).dayOfWeek == DayOfWeek.SUNDAY })
        val req = server.takeRequest()
        assertEquals("Bearer tok", req.headers["Authorization"])
        assertEquals("/weeks?from=2026-10-04&count=8", req.target)
    }

    @Test fun `health is the only call sent without the token`() = runTest {
        reply(fixture("health.json")); reply(fixture("default_week.json"))
        assertTrue(api.health().ok)
        assertNull(server.takeRequest().headers["Authorization"])
        api.defaultCartWeek()
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"])
    }

    @Test fun `parses captured week entries with titles`() = runTest {
        reply(fixture("week_2026-10-11.json"))
        val e = api.week("2026-10-11").first { it.id == 1 }
        assertEquals(1, e.recipeId); assertNull(e.day); assertEquals("Best Chocolate Chip Cookies", e.title)
    }

    @Test fun `parses captured recipes and recipe detail`() = runTest {
        reply(fixture("recipes.json")); reply(fixture("recipe_3.json"))
        assertTrue(api.recipes("newest").any { it.id == 1 && it.source == "nyt" && it.sourceUrl!!.startsWith("https://cooking.nytimes.com/") })
        val r = api.recipe(3)
        assertEquals("Thai Green Curry Wings", r.title)
        assertTrue(r.ingredients.first { it.name == "salt" }.likelyOnHand)
        assertEquals(0, r.ratings.timesRated)
    }

    @Test fun `parses the captured sent draft`() = runTest {
        reply(fixture("draft_3_sent.json"))
        val d = api.draft(3)
        assertEquals("sent", d.status)
        assertEquals("24b34302-2508-4795-ab5d-2f2f9a7de03c", d.pcxCartId)
        assertEquals(11, d.lines.size)
        assertEquals(1, d.lines.count { it.removed })
        assertEquals(5, d.lines[0].alternatives.size)
        assertEquals("1 kg", d.lines[0].product!!.packageSize)
    }

    @Test fun `parses synthetic replies`() = runTest {
        reply(fixture("share_result.json")); reply(fixture("draft_ready.json")); reply(fixture("prep_plan_ready.json"))
        reply(fixture("prep_plan_building.json")); reply(fixture("cook_card.json")); reply(fixture("pending.json"))
        val s = api.share(ShareIn("x", "2026-10-11"))
        assertTrue(s.existing); assertEquals(listOf(1), s.recipe.missingPages); assertEquals(4.5, s.recipe.ratings.avgFamily!!, 0.0)
        val d = api.draft(7)
        assertNull(d.lines[1].product); assertEquals("memory", d.lines[0].source)
        assertEquals(1, d.lines[0].quantity); assertNull(d.lines[1].quantity)
        val p = api.prepPlan(4)
        assertEquals(listOf("knife", "sauces", "proteins", "pack"), p.sections.map { it.key })
        assertEquals("freeze_then_thaw", p.sections[2].tasks[0].shelfLife)
        assertEquals(4, p.checklist.total)
        val b = api.prepPlan(5)
        assertEquals("building", b.status); assertEquals(emptyList<String>(), b.warnings); assertNull(b.totalMinutes)
        val c = api.card(21)
        assertEquals(20, c.steps[1].timerMinutes); assertEquals(listOf("Chop the cilantro"), c.dayOf)
        assertEquals("Chili", api.pendingRatings("2026-10-14").single().title)
    }

    @Test fun `unplacing sends an explicit null day`() = runTest {
        noContent()
        api.patchEntry(5, Bodies.entryPatch(unplace = true))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method); assertEquals("/plan/5", req.target)
        assertEquals("""{"day":null}""", req.body!!.utf8())
    }

    @Test fun `patch bodies carry only the given fields`() = runTest {
        noContent(); api.patchEntry(5, Bodies.entryPatch(day = 3))
        assertEquals("""{"day":3}""", sentBody())
        noContent(); api.patchEntry(5, Bodies.entryPatch(multiplier = 1.5))
        assertEquals("""{"multiplier":1.5}""", sentBody())
        reply("""{"id":31,"item_key":"onion|each","name":"onion","quantity":0,"removed":true}""")
        api.patchLine(7, 31, Bodies.linePatch(quantity = 0))
        assertEquals("""{"quantity":0}""", sentBody())
    }

    @Test fun `null rating fields and week are omitted`() = runTest {
        noContent(); api.putRating(7, RatingIn(family = 4))
        assertEquals("""{"family":4}""", sentBody())
        reply(fixture("share_result.json")); api.share(ShareIn("https://cooking.nytimes.com/recipes/1-x"))
        assertEquals("""{"text":"https://cooking.nytimes.com/recipes/1-x"}""", sentBody())
    }

    @Test fun `book search parses hits and sends the query`() = runTest {
        reply(fixture("books_search.json"))
        val hits = api.searchBooks("imaginary larder", 8)
        assertEquals("/books/search?q=imaginary%20larder&limit=8", server.takeRequest().target)
        assertEquals(3, hits.size)
        assertEquals(BookHit("The Imaginary Larder", "Suppers from an Invented Pantry", listOf("Ada Pepper", "Basil Thyme"), 1999,
            "9780000000017", "Imaginary Press", "openlibrary"), hits[0])
        assertEquals(BookHit("Imaginary Larder Two", null, listOf("Ada Pepper"), 2004, source = "openlibrary"), hits[1])
        assertEquals("google", hits[2].source)                         // unknown fields ignored
        reply("[]"); assertTrue(api.searchBooks("zz", 8).isEmpty())
    }

    @Test fun `source patch sends every source field, null clears`() = runTest {
        reply(fixture("recipe_11.json"))
        val r = api.patchRecipe(11, Bodies.sourcePatch("Invented Bakes", null, "Ada Pepper", "9780000000017"))
        assertEquals("""{"source_kind":"book","source_title":"Invented Bakes","source_ref":null,"source_author":"Ada Pepper","source_isbn":"9780000000017"}""",
            sentBody())
        assertNull(r.sourceAuthor)
        reply(fixture("recipe_11.json").replace("\"source_ref\": null", "\"source_ref\": null, \"source_author\": \"Ada Pepper\", \"source_isbn\": \"9780000000017\""))
        val withBook = api.recipe(11)
        assertEquals("Ada Pepper" to "9780000000017", withBook.sourceAuthor to withBook.sourceIsbn)
    }

    @Test fun `photo upload is multipart with pages in order and the week`() = runTest {
        val a = tmp.newFile("x.jpg").apply { writeBytes(byteArrayOf(1)) }
        val b = tmp.newFile("y.jpg").apply { writeBytes(byteArrayOf(2)) }
        reply(fixture("share_result.json"))
        api.photo(Http.pageParts(listOf(a, b)), Http.textPart("2026-10-11"), null)
        val body = sentBody()
        val p1 = body.indexOf("""name="files"; filename="page01.jpg"""")
        val p2 = body.indexOf("""name="files"; filename="page02.jpg"""")
        assertTrue(p1 in 0 until p2)
        assertTrue(body.contains("name=\"week\"") && body.contains("2026-10-11"))
        assertFalse(body.contains("name=\"title\""))
    }

    @Test fun `share, photo and pages get the long read timeout`() {
        assertTrue(Http.isLongCall("/recipes/share"))
        assertTrue(Http.isLongCall("/recipes/photo"))
        assertTrue(Http.isLongCall("/recipes/12/pages"))
        assertTrue(Http.isLongCall("/prefix/recipes/photo"))
        assertFalse(Http.isLongCall("/recipes"))
        assertFalse(Http.isLongCall("/recipes/12"))
    }
}
