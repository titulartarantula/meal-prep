package dev.mealprep.app.data

import dev.mealprep.app.TestEnv
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.ApplyLine
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.data.api.Suggestion
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.fixture
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeRepositoryTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    @Test fun `the fixtures decode`() {
        val items = Http.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(
            dev.mealprep.app.data.api.FridgeItem.serializer()), fixture("fridge.json"))
        assertEquals(listOf("Cilantro", "Eggs", "Spinach", "Yogurt"), items.map { it.name })
        assertEquals("bunch", items[0].unit); assertTrue(items[0].soon); assertEquals(9, items[0].daysSinceChecked)
        assertNull(items[3].qty); assertTrue(items[2].qtyEstimated); assertEquals("2026-10-20", items[1].useBy)
        val s = Http.json.decodeFromString(Suggestion.serializer(), fixture("fridge_suggest.json"))
        assertEquals(listOf(5, 7), s.picks.map { it.recipeId })
        assertEquals(listOf("4 Eggs", "1 bag Spinach", "Cilantro"), s.picks[0].uses.map { it.amount })
        assertTrue(s.picks[0].uses[2].stale); assertEquals(2, s.picks[1].stillNeedMore); assertEquals(4.5, s.picks[0].ratings.avgFamily)
        val read = Http.json.decodeFromString(FridgeRead.serializer(), fixture("fridge_read_ready.json"))
        assertEquals(listOf("amount", "seen", "new", "new", "not_seen"), read.proposals.map { it.kind })
        assertEquals(4.0, read.proposals[0].estimate!!.qty); assertEquals("2026-10-01", read.proposals[0].current!!.amountAsOf)
        assertEquals(2, read.count("new")); assertFalse(read.proposals[4].defaultOn)
        val used = Http.json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(
            dev.mealprep.app.data.api.FridgeNight.serializer()), fixture("fridge_used.json"))
        assertEquals("3 cups left", used[0].uses[1].after); assertEquals(listOf("Cilantro"), used[0].check)
    }

    @Test fun `a list line from an older server has no fridge state`() {
        val old = Http.json.decodeFromString(ListItem.serializer(), """{"key":"egg|each","name":"egg","qty":4}""")
        assertNull(old.fridge); assertNull(old.fridgeQty); assertEquals(emptyList<Int>(), old.fridgeItems)
        val now = Http.json.decodeFromString(ListItem.serializer(),
            """{"key":"egg|each","name":"egg","qty":6,"fridge":"partial","fridge_qty":6,"fridge_items":[1]}""")
        assertEquals("partial", now.fridge); assertEquals(6.0, now.fridgeQty); assertEquals(listOf(1), now.fridgeItems)
    }

    @Test fun `the fridge shows the saved copy offline`() = runTest {
        env.on("GET", "/fridge", body = fixture("fridge.json"))
        assertEquals(4, env.repo.fridge().value!!.size)
        env.offline = true
        val saved = env.repo.fridge()
        assertEquals(4, saved.value!!.size); assertTrue(saved.offline); assertEquals(ApiError.Unreachable, saved.error)
    }

    @Test fun `adding sends only what was given and a duplicate says which item`() = runTest {
        env.on("POST", "/fridge", code = 409, body = """{"detail":"Already in the fridge: 6 Eggs","id":1}""")
        val r = env.repo.addFridge("Eggs", 2.0, null, useSoon = false, useBy = null, fromList = true)
        val e = (r as ApiResult.Err).error
        assertEquals(ApiError.Exists("Already in the fridge: 6 Eggs", 1), e)
        assertEquals("Already in the fridge: 6 Eggs", e.userMessage())
        assertEquals("""{"name":"Eggs","qty":2.0,"use_soon":false,"from_list":true}""", env.bodies("POST", "/fridge").last())
    }

    @Test fun `a 409 without an id stays an http error`() = runTest {
        env.on("POST", "/fridge/reads/12/apply", code = 409, body = """{"detail":"These changes were already saved (on the other phone?).","read":{"id":12,"status":"applied"}}""")
        val e = (env.repo.applyRead(12, emptyList()) as ApiResult.Err).error
        assertEquals(ApiError.Http(409, "These changes were already saved (on the other phone?)."), e)
    }

    @Test fun `422 reads the server's sentence`() = runTest {
        env.on("POST", "/fridge", code = 422, body = """{"detail":[{"msg":"Value error, unknown unit 'furlong'"}]}""")
        assertEquals(422, ((env.repo.addFridge("Eggs", 1.0, "furlong") as ApiResult.Err).error as ApiError.Http).code)
    }

    @Test fun `edits send explicit nulls for a cleared amount and confirm alone`() = runTest {
        env.on("PATCH", "/fridge/1", body = """{"id":1,"name":"Eggs","amount_as_of":"2026-10-07"}""")
        env.repo.editFridge(1, name = "Eggs", qty = null, unit = null, useSoon = true, useBy = null)
        assertEquals("""{"name":"Eggs","qty":null,"unit":null,"use_soon":true,"use_by":null}""", env.bodies("PATCH", "/fridge/1").last())
        env.repo.confirmFridge(1)
        assertEquals("""{"confirm":true}""", env.bodies("PATCH", "/fridge/1").last())
    }

    @Test fun `suggest, names, the nudge and the list carry the fridge`() = runTest {
        env.on("POST", "/fridge/suggest", body = fixture("fridge_suggest.json"))
        env.on("GET", "/fridge/names", body = """["egg","eggplant"]""")
        env.on("GET", "/fridge/used", body = fixture("fridge_used.json"))
        env.on("POST", "/fridge/used/31", body = """{"entry_id":31,"action":"applied","changes":[{"item_id":1,"name":"Eggs","before":6,"after":2,"unit":null,"text":"Eggs 2"}],"answered_at":"2026-10-07T12:00:00+00:00"}""")
        env.on("POST", "/list", body = "[]")
        val wk = LocalDate.parse("2026-10-11")
        (env.repo.suggest(3, 4, wk, listOf(9)) as ApiResult.Ok)
        assertEquals("""{"nights":3,"people":4,"week":"2026-10-11","exclude":[9]}""", env.bodies("POST", "/fridge/suggest").last())
        assertEquals(listOf("egg", "eggplant"), (env.repo.fridgeNames("eg") as ApiResult.Ok).value)
        assertEquals("eg", env.requests.last { it.url.encodedPath == "/fridge/names" }.url.queryParameter("q"))
        assertEquals(31, (env.repo.fridgeUsed(LocalDate.parse("2026-10-07")) as ApiResult.Ok).value.single().entryId)
        assertEquals("Eggs 2", (env.repo.answerUsed(31, true) as ApiResult.Ok).value.changes.single().text)
        assertEquals("""{"apply":true}""", env.bodies("POST", "/fridge/used/31").last())
        env.repo.shoppingList(listOf(wk), listOf(1), fridge = true)
        assertTrue(env.bodies("POST", "/list").last().contains("\"fridge\":true"))
        env.repo.shoppingList(listOf(wk))
        assertTrue(env.bodies("POST", "/list").last().contains("\"fridge\":false"))
    }

    @Test fun `apply sends only the decisions, a read keeps an offline copy, and the open read may be none`() = runTest {
        env.on("POST", "/fridge/reads/12/apply", body = fixture("fridge_read_ready.json").replace("\"ready\"", "\"applied\""))
        env.on("GET", "/fridge/reads/12", body = fixture("fridge_read_ready.json"))
        env.on("GET", "/fridge/reads", body = """{"read":null}""")
        env.repo.applyRead(12, listOf(ApplyLine("p1", true), ApplyLine("p3", true, name = "Milk", qty = 2.0, unit = "l"),
            ApplyLine("p5", false)))
        assertEquals("""{"lines":[{"pid":"p1","accept":true},{"pid":"p3","accept":true,"name":"Milk","qty":2.0,"unit":"l"},{"pid":"p5","accept":false}]}""",
            env.bodies("POST", "/fridge/reads/12/apply").last())
        assertEquals(5, env.repo.fridgeRead(12).value!!.proposals.size)
        env.offline = true
        assertEquals(5, env.repo.fridgeRead(12).value!!.proposals.size)
        env.offline = false
        assertNull((env.repo.openFridgeRead() as ApiResult.Ok).value)
    }

    @Test fun `photos go up as files in order`() = runTest {
        env.on("POST", "/fridge/photos", code = 202, body = """{"id":12,"status":"reading","photo_count":2,"progress":"Reading 2 photos…","proposals":[],"counts":{},"existing":false}""")
        val a = java.io.File.createTempFile("aaa", ".jpg").apply { writeBytes(byteArrayOf(1)) }
        val b = java.io.File.createTempFile("bbb", ".jpg").apply { writeBytes(byteArrayOf(2)) }
        val r = (env.repo.uploadFridgePhotos(listOf(a, b)) as ApiResult.Ok).value
        assertEquals(12, r.id); assertEquals("reading", r.status)
        val body = env.bodies("POST", "/fridge/photos").last()
        assertTrue(body.indexOf("page01.jpg") in 0 until body.indexOf("page02.jpg"))
    }
}
