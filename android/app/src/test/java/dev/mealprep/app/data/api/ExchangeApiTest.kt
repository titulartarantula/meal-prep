package dev.mealprep.app.data.api

import dev.mealprep.app.fixture
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

/** Import / export calls (0.9.0): DTOs against invented replies of the server's documented shape. */
class ExchangeApiTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private lateinit var api: MealPrepApi

    @Before fun setUp() { server.start(); api = Http.api(server.url("/").toString(), Http.client({ "tok" })) }
    @After fun tearDown() { server.close() }

    private fun reply(body: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())

    @Test fun `the preview decodes, items and counts`() = runTest {
        reply(fixture("import_preview.json"))
        val f = tmp.newFile("x.json").apply { writeText("{}") }
        val r = api.importRecipes(Http.filePart(f, "x.json"), Http.textPart("true"))
        assertTrue(r.dryRun); assertEquals("jsonld", r.format)
        assertEquals(mapOf("new" to 2, "duplicate" to 3, "failed" to 1), r.counts)
        assertEquals(6, r.items.size)
        val soup = r.items[0]
        assertEquals("0:9f2c1a7e", soup.key); assertEquals("new", soup.status); assertEquals("add", soup.action)
        assertTrue(soup.aiTidy); assertNull(soup.match); assertEquals(9, soup.ingredients)
        assertEquals(ImportMatch(17, "Test Bean Chili", "id"), r.items[2].match)
        assertTrue(r.items[3].canUpdate); assertTrue(r.items[4].canAddAnyway)
        assertNull(r.items[5].title); assertEquals(listOf("No recipe name"), r.items[5].reasons)
        assertNull(r.id); assertNull(r.status)
    }

    @Test fun `the job decodes with progress and apply counts`() = runTest {
        reply(fixture("import_job_running.json"), code = 202)
        val j = api.importJob(7)
        assertEquals(7, j.id); assertEquals("running", j.status); assertEquals(Progress(1, 3), j.progress)
        assertEquals(2, j.count("pending")); assertEquals(0, j.count("nothing"))
        assertFalse(j.existing); assertFalse(j.dryRun)
        assertEquals(31, j.items[0].recipeId)
        assertEquals("/imports/7", server.takeRequest().target)
    }

    @Test fun `import is multipart with the file, dry_run and choices`() = runTest {
        reply(fixture("import_job_running.json").replace("\"existing\": false", "\"existing\": true"), code = 202)
        val f = tmp.newFile("abc.json").apply { writeText("""{"@type":"Recipe","name":"Test Soup"}""") }
        val job = api.importRecipes(Http.filePart(f, "abc.json"), Http.textPart("false"), Http.textPart("""{"0:9f2c1a7e":"add"}"""))
        assertTrue(job.existing)
        val req = server.takeRequest()
        assertEquals("POST", req.method); assertEquals("/recipes/import", req.target)
        assertEquals("Bearer tok", req.headers["Authorization"])
        val body = req.body!!.utf8()
        assertTrue(body.contains("""name="file"; filename="abc.json""""))
        assertTrue(body.contains("""{"@type":"Recipe","name":"Test Soup"}"""))
        assertTrue(body.contains("name=\"dry_run\"") && body.contains("\r\n\r\nfalse\r\n"))
        assertTrue(body.contains("name=\"choices\"") && body.contains("""{"0:9f2c1a7e":"add"}"""))
    }

    @Test fun `a preview sends no choices`() = runTest {
        reply(fixture("import_preview.json"))
        api.importRecipes(Http.filePart(tmp.newFile("p.json"), "p.json"), Http.textPart("true"))
        assertFalse(server.takeRequest().body!!.utf8().contains("choices"))
    }

    @Test fun `export streams the body and keeps the headers`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/ld+json; charset=utf-8")
            .addHeader("Content-Disposition", "attachment; filename=\"test-lentil-soup.recipe.json\"")
            .body("""{"@type":"Recipe","name":"Test Lentil Soup"}""").build())
        val r = api.exportRecipe(4)
        assertEquals("attachment; filename=\"test-lentil-soup.recipe.json\"", r.headers()["Content-Disposition"])
        assertEquals("""{"@type":"Recipe","name":"Test Lentil Soup"}""", r.body()!!.string())
        assertEquals("/recipes/4/export", server.takeRequest().target)
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        api.exportLibrary()
        assertEquals("/recipes/export", server.takeRequest().target)
    }

    @Test fun `old recipe replies without the new fields still decode`() = runTest {
        reply(fixture("recipe_3.json"))
        val r = api.recipe(3)
        assertNull(r.uid); assertNull(r.description); assertNull(r.prepMinutes); assertNull(r.yieldText)
        assertEquals(0, r.ratings.importedRatings)
    }

    @Test fun `the new recipe fields decode`() = runTest {
        reply("""{"id":31,"title":"Test Lentil Soup","source":"import","uid":"7d1e3f0a-0000-4000-8000-000000000001",
            "description":"A soup.","notes":"Less salt.","prep_minutes":10,"cook_minutes":40,"total_minutes":50,
            "yield_text":"Makes 6 bowls","image":"https://recipes.example.org/soup.jpg","schema_extra":{"keywords":"soup"},
            "ratings":{"times_cooked":3,"times_rated":3,"avg_family":4.0,"imported_ratings":2}}""")
        val r = api.recipe(31)
        assertEquals("7d1e3f0a-0000-4000-8000-000000000001", r.uid)
        assertEquals(listOf(10, 40, 50), listOf(r.prepMinutes, r.cookMinutes, r.totalMinutes))
        assertEquals("Makes 6 bowls", r.yieldText); assertEquals("Less salt.", r.notes)
        assertEquals(2, r.ratings.importedRatings)
    }

    @Test fun `import and export get the transfer timeout`() {
        assertTrue(Http.isTransfer("/recipes/import")); assertTrue(Http.isTransfer("/recipes/export"))
        assertTrue(Http.isTransfer("/recipes/12/export"))
        assertFalse(Http.isTransfer("/recipes")); assertFalse(Http.isTransfer("/imports/7"))
        assertFalse(Http.isLongCall("/recipes/import"))
    }
}
