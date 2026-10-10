package dev.mealprep.app.data

import dev.mealprep.app.TestEnv
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.fixture
import dev.mealprep.app.ui.exchange.ExchangeText
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExchangeRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private fun file(text: String = """{"@type":"Recipe","name":"Test Soup"}""") = tmp.newFile().apply { writeText(text) }

    @Test fun `a recipe export is saved under the server's file name`() = runTest {
        env.on("GET", "/recipes/4/export", body = """{"@type":"Recipe","name":"Test Lentil Soup"}""",
            headers = mapOf("Content-Disposition" to "attachment; filename=\"test-lentil-soup.recipe.json\""))
        val dir = tmp.newFolder("exports")
        val r = (env.repo.exportRecipe(4, dir) as ApiResult.Ok).value
        assertEquals("test-lentil-soup.recipe.json", r.name)
        assertEquals(File(dir, "test-lentil-soup.recipe.json"), r.file)
        assertEquals("""{"@type":"Recipe","name":"Test Lentil Soup"}""", r.file.readText())
    }

    @Test fun `without a Content-Disposition the phone names the file`() = runTest {
        env.on("GET", "/recipes/4/export", body = "{}")
        env.on("GET", "/recipes/export", body = """{"@graph":[]}""")
        val dir = tmp.newFolder("exports")
        assertEquals("recipe-4.recipe.json", (env.repo.exportRecipe(4, dir) as ApiResult.Ok).value.name)
        assertEquals("meal-prep-recipes-2026-10-07.json", (env.repo.exportLibrary(dir) as ApiResult.Ok).value.name)
    }

    @Test fun `export errors, unknown recipe and offline`() = runTest {
        val dir = tmp.newFolder("exports")
        val gone = (env.repo.exportRecipe(99, dir) as ApiResult.Err).error   // TestEnv: unknown route → 404
        assertEquals(ExchangeText.RECIPE_GONE, ExchangeText.exportError(gone))
        env.offline = true
        assertEquals(ApiError.Unreachable, (env.repo.exportLibrary(dir) as ApiResult.Err).error)
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun `preview sends the file with dry_run true`() = runTest {
        env.on("POST", "/recipes/import", body = fixture("import_preview.json"))
        val r = (env.repo.importPreview(file()) as ApiResult.Ok).value
        assertEquals(6, r.items.size)
        val body = env.bodies("POST", "/recipes/import").single()
        assertTrue(body.contains("name=\"dry_run\"") && body.contains("\r\n\r\ntrue\r\n"))
        assertTrue(body.contains("""{"@type":"Recipe","name":"Test Soup"}"""))
    }

    @Test fun `apply sends the choices as a JSON object and returns the job`() = runTest {
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_job_running.json"))
        val job = (env.repo.importApply(file(), linkedMapOf("0:9f2c1a7e" to "add", "3:8c9d0e1f" to "update", "4:2a3b4c5d" to "skip"))
            as ApiResult.Ok).value
        assertEquals(7, job.id)
        val body = env.bodies("POST", "/recipes/import").single()
        assertTrue(body.contains("\r\n\r\nfalse\r\n"))
        assertTrue(body.contains("""{"0:9f2c1a7e":"add","3:8c9d0e1f":"update","4:2a3b4c5d":"skip"}"""))
    }

    @Test fun `413 and 422 come back with the server's words, mapped to ours`() = runTest {
        env.on("POST", "/recipes/import", code = 413, body = """{"detail":"This file is too big to import (max 20 MB)."}""")
        val big = (env.repo.importPreview(file()) as ApiResult.Err).error
        assertEquals(ApiError.Http(413, "This file is too big to import (max 20 MB)."), big)
        assertTrue(ExchangeText.importError(big).startsWith("This file is too big to import (max 20 MB)."))
        env.on("POST", "/recipes/import", code = 422, body = """{"detail":"No recipes found in this file."}""")
        assertEquals(ExchangeText.NO_RECIPES, ExchangeText.importError((env.repo.importPreview(file()) as ApiResult.Err).error))
        env.token = "bad"
        env.on("POST", "/recipes/import", code = 401, body = """{"detail":"Unauthorized"}""")
        assertEquals(ApiError.Unauthorized, (env.repo.importPreview(file()) as ApiResult.Err).error)
    }

    @Test fun `the job's last copy is kept for offline`() = runTest {
        env.on("GET", "/imports/7", body = fixture("import_job_done.json"))
        assertEquals("done", env.repo.importJob(7).value!!.status)
        env.offline = true
        val saved = env.repo.importJob(7)
        assertEquals("done", saved.value!!.status); assertTrue(saved.offline)
        assertEquals(ApiError.Unreachable, saved.error)
    }

    @Test fun `preview and apply send the file's name for a document's source`() = runTest {
        env.on("POST", "/recipes/import", body = fixture("import_preview.json"))
        env.repo.importPreview(file(), "  Summer   salads.pdf ")
        val sent = env.bodies("POST", "/recipes/import").single()
        assertTrue(sent.contains("name=\"name\"") && sent.contains("\r\n\r\nSummer salads.pdf\r\n"))   // tidied
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_job_running.json"))
        env.repo.importApply(file(), mapOf("0:9f2c1a7e" to "add"), "Summer salads.pdf")
        assertTrue(env.bodies("POST", "/recipes/import").last().contains("\r\n\r\nSummer salads.pdf\r\n"))
        env.on("POST", "/recipes/import", body = fixture("import_preview.json"))
        env.repo.importPreview(file())
        assertFalse(env.bodies("POST", "/recipes/import").last().contains("name=\"name\""))   // no name: no part
    }

    @Test fun `a document's first preview is its read job`() = runTest {
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_read_running.json"))
        val r = (env.repo.importPreview(file(), "cookbook.pdf") as ApiResult.Ok).value
        assertEquals(9, r.id); assertEquals("running", r.status); assertTrue(r.dryRun); assertEquals("pdf", r.format)
        assertEquals(1, r.progress.done); assertEquals(3, r.progress.total); assertTrue(r.items.isEmpty())
    }
}
