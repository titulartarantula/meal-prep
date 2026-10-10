package dev.mealprep.app.ui.exchange

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.core.ImportFiles
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.ImportReport
import dev.mealprep.app.fixture
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val env = TestEnv()
    private val vms = mutableListOf<ImportViewModel>()
    private val watched = mutableListOf<Int>()

    @After fun tearDown() {
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } }
        env.close()
    }

    private fun file() = tmp.newFile().apply { writeText("""[{"@type":"Recipe","name":"Test Lentil Soup"}]""") }
    private fun vm(f: File? = file(), saved: SavedStateHandle = SavedStateHandle(), jobId: Int = 0, error: String? = null) =
        ImportViewModel(env.repo, f, "recipes.json", error, jobId, saved, watch = { watched += it }, pollMs = 20).also { vms += it }

    private fun preview() = env.on("POST", "/recipes/import", body = fixture("import_preview.json"))

    @Test fun `the preview ticks the new recipes`() = runTest {
        preview()
        val s = vm().state.await { it.report != null }
        assertEquals(setOf("0:9f2c1a7e", "1:0b1c2d3e"), s.ticks)
        assertEquals("Add 2 recipes", s.buttonLabel); assertTrue(s.canApply)
        assertEquals("recipes.json", s.name)
        assertTrue(env.bodies("POST", "/recipes/import").single().contains("\r\n\r\ntrue\r\n"))
    }

    @Test fun `ticking and unticking, never a plain duplicate or a failed recipe`() = runTest {
        preview()
        val vm = vm(); vm.state.await { it.report != null }
        vm.toggle("0:9f2c1a7e"); vm.toggle("1:0b1c2d3e")
        assertEquals("Nothing selected", vm.state.value.buttonLabel); assertFalse(vm.state.value.canApply)
        vm.toggle("2:4e5f6a7b"); vm.toggle("5:6e7f8a9b"); vm.toggle("nope")
        assertTrue(vm.state.value.ticks.isEmpty())
        vm.toggle("3:8c9d0e1f"); vm.toggle("4:2a3b4c5d")
        assertEquals("Add 1, update 1", vm.state.value.buttonLabel)
    }

    @Test fun `apply sends the choices, watches the job and polls it to the result`() = runTest {
        preview()
        val f = file()
        val vm = vm(f = f); vm.state.await { it.report != null }
        vm.toggle("3:8c9d0e1f")
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_job_running.json"))
        env.onSequence("GET", "/imports/7", listOf(fixture("import_job_running.json"), fixture("import_job_done.json")))
        vm.apply()
        val s = vm.state.await { it.job?.status == "done" }
        val sent = env.bodies("POST", "/recipes/import").last()
        assertTrue(sent.contains("\r\n\r\nfalse\r\n"))
        assertTrue(sent.contains(""""0:9f2c1a7e":"add""""))
        assertTrue(sent.contains(""""2:4e5f6a7b":"skip""""))
        assertTrue(sent.contains(""""3:8c9d0e1f":"update""""))
        assertFalse(sent.contains("5:6e7f8a9b"))                       // a failed recipe isn't sent
        assertEquals(listOf(7), watched)
        assertFalse(f.exists())                                           // the copy is gone once the job has it
        assertEquals("Added 2 recipes. Updated 1 recipe. 1 was already in Recipes. 1 couldn't be added.", ImportLogic.result(s.job!!))
    }

    @Test fun `apply twice sends once`() = runTest {
        preview()
        val vm = vm(); vm.state.await { it.report != null }
        env.onDelayed("POST", "/recipes/import", 300, fixture("import_job_done.json").replace("\"existing\": false", "\"existing\": true"))
        vm.apply(); vm.apply()
        vm.state.await { it.job != null }
        assertEquals(2, env.count("POST", "/recipes/import"))            // the preview + one apply
        assertEquals(0, env.count("GET", "/imports/7"))                   // already done: nothing to poll
        assertFalse(vm.state.value.canApply)
    }

    @Test fun `apply offline says so and keeps the ticks`() = runTest {
        preview()
        val vm = vm(); vm.state.await { it.report != null }
        env.offline = true
        vm.apply()
        val s = vm.state.await { it.applyError != null }
        assertEquals(ExchangeText.OFFLINE_IMPORT, s.applyError); assertEquals(2, s.ticks.size); assertTrue(s.canApply)
    }

    @Test fun `whole-file errors in plain words, Try again reads again`() = runTest {
        env.on("POST", "/recipes/import", code = 422, body = """{"detail":"This file isn't valid JSON."}""")
        val vm = vm()
        val s = vm.state.await { it.error != null }
        assertEquals(ExchangeText.NOT_JSON, s.error); assertFalse(s.canRetry)      // the same file would fail again
        env.on("POST", "/recipes/import", code = 413, body = """{"detail":"This file is too big to import (max 20 MB)."}""")
        vm.preview()
        assertTrue(vm.state.await { it.error?.startsWith("This file is too big") == true }.error!!.contains("Export fewer"))
        env.offline = true
        vm.preview()
        assertTrue(vm.state.await { it.error == ExchangeText.OFFLINE_IMPORT }.canRetry)
    }

    @Test fun `a copy error from the route, or a missing file, never calls the server`() = runTest {
        val a = vm(error = "This file is too big to import (max 20 MB). Export fewer recipes at a time, then import each file.")
        assertTrue(a.state.value.error!!.startsWith("This file is too big"))
        assertFalse(a.state.value.canRetry)
        val b = vm(f = File(tmp.root, "gone.json"))
        assertEquals(ImportViewModel.FILE_GONE, b.state.value.error)
        assertEquals(0, env.count("POST", "/recipes/import"))
    }

    @Test fun `another file chosen after an error is read`() = runTest {
        preview()
        val vm = vm(error = ImportFiles.UNREADABLE)
        assertEquals(0, env.count("POST", "/recipes/import"))
        vm.newFile(file(), "other.json")
        val s = vm.state.await { it.report != null }
        assertEquals("other.json", s.name); assertNull(s.error)
        vm.fileError(ImportFiles.TOO_BIG)
        assertEquals(ImportFiles.TOO_BIG, vm.state.value.error); assertNull(vm.state.value.report)
    }

    @Test fun `ticks survive process death`() = runTest {
        preview()
        val saved = SavedStateHandle()
        val first = vm(saved = saved); first.state.await { it.report != null }
        first.toggle("0:9f2c1a7e"); first.toggle("4:2a3b4c5d")
        val again = vm(saved = saved)
        assertEquals(setOf("1:0b1c2d3e", "4:2a3b4c5d"), again.state.await { it.report != null }.ticks)
    }

    @Test fun `a started job is reopened after process death and from a notification`() = runTest {
        env.on("GET", "/imports/7", body = fixture("import_job_done.json"))
        val saved = SavedStateHandle(mapOf(ImportViewModel.JOB to 7))
        assertEquals("done", vm(saved = saved).state.await { it.job != null }.job!!.status)
        assertEquals("done", vm(f = null, jobId = 7).state.await { it.job != null }.job!!.status)
        assertEquals(0, env.count("POST", "/recipes/import"))
    }

    @Test fun `the job's offline copy shows away from home`() = runTest {
        env.on("GET", "/imports/7", body = fixture("import_job_done.json"))
        env.repo.importJob(7)
        env.offline = true
        val s = vm(f = null, jobId = 7).state.await { it.job != null }
        assertEquals("done", s.job!!.status); assertTrue(s.offlineSince != null)
    }

    @Test fun `an unknown job says so`() = runTest {
        val s = vm(f = null, jobId = 99).state.await { it.jobError != null }   // TestEnv: 404
        assertEquals(ExchangeText.GONE, s.jobError); assertNull(s.job)
    }

    @Test fun `a file changed since the preview shows on the item`() = runTest {
        preview()
        val vm = vm(); vm.state.await { it.report != null }
        val base = Http.json.decodeFromString(ImportReport.serializer(), fixture("import_job_done.json"))
        val changed = base.copy(items = base.items.map {
            if (it.key == "1:0b1c2d3e") it.copy(status = "failed", reasons = listOf("The file changed since the preview; preview it again.")) else it
        })
        env.on("POST", "/recipes/import", code = 202, body = Http.json.encodeToString(ImportReport.serializer(), changed))
        vm.apply()
        val job = vm.state.await { it.job != null }.job!!
        assertTrue(job.items.any { "The file changed since the preview; preview it again." in it.reasons })
    }

    // --- recipe documents ---

    private fun readFailed(error: String) = fixture("import_read_running.json")
        .replace("\"status\": \"running\"", "\"status\": \"failed\"").replace("\"error\": null", "\"error\": \"$error\"")

    @Test fun `a document is read by the server, polled, then previewed`() = runTest {
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_read_running.json"))
        env.onSequence("GET", "/imports/9", listOf(fixture("import_read_running.json"), fixture("import_read_done.json")))
        val vm = vm()
        val reading = vm.state.await { it.reading != null }
        assertTrue(reading.loading); assertNull(reading.report)
        assertEquals("Part 2 of 3", ImportLogic.readProgress(reading.reading!!))
        val s = vm.state.await { it.report != null }
        assertFalse(s.loading); assertNull(s.reading); assertNull(s.error)
        assertEquals(setOf("0:9f2c1a7e", "1:0b1c2d3e"), s.ticks); assertEquals("Add 2 recipes", s.buttonLabel)
        assertTrue(env.bodies("POST", "/recipes/import").single().contains("\r\n\r\nrecipes.json\r\n"))   // the name
        assertTrue(watched.isEmpty())                                      // a read isn't an import job to watch
    }

    @Test fun `a document read before answers at once`() = runTest {
        env.on("POST", "/recipes/import", body = fixture("import_read_done.json"))
        val s = vm().state.await { it.report != null }
        assertEquals("pdf", s.report!!.format); assertEquals(0, env.count("GET", "/imports/9"))
    }

    @Test fun `a failed read says why and offers Try again, which reads again`() = runTest {
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_read_running.json"))
        env.on("GET", "/imports/9", body = readFailed("No recipes found in this document."))
        val vm = vm()
        val s = vm.state.await { it.error != null }
        assertEquals(ExchangeText.NO_DOC_RECIPES, s.error); assertTrue(s.canRetry); assertNull(s.reading); assertFalse(s.loading)
        env.on("GET", "/imports/9", body = fixture("import_read_done.json"))
        vm.preview()
        assertEquals(6, vm.state.await { it.report != null }.report!!.items.size)
        assertEquals(2, env.count("POST", "/recipes/import"))
    }

    @Test fun `the AI being down while reading says so`() = runTest {
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_read_running.json"))
        env.on("GET", "/imports/9", body = readFailed("The AI couldn't read this document. Try again in a few minutes."))
        assertEquals(ExchangeText.AI_DOWN, vm().state.await { it.error != null }.error)
    }

    @Test fun `reading carries on through a Wi-Fi drop`() = runTest {
        env.on("POST", "/recipes/import", code = 202, body = fixture("import_read_running.json"))
        env.on("GET", "/imports/9", body = fixture("import_read_running.json"))
        val vm = vm()
        vm.state.await { it.reading != null }
        env.offline = true
        withContext(Dispatchers.Default) { Thread.sleep(300) }              // polls offline: the saved copy, no error
        assertNull(vm.state.value.error); assertTrue(vm.state.value.loading)
        env.offline = false
        env.on("GET", "/imports/9", body = fixture("import_read_done.json"))
        assertEquals(6, vm.state.await { it.report != null }.report!!.items.size)
    }

    @Test fun `select all and select none`() = runTest {
        preview()
        val vm = vm(); vm.state.await { it.report != null }
        vm.selectAll(true)
        assertEquals(setOf("0:9f2c1a7e", "1:0b1c2d3e", "3:8c9d0e1f", "4:2a3b4c5d"), vm.state.value.ticks)
        assertEquals("Add 3, update 1", vm.state.value.buttonLabel)
        vm.selectAll(false)
        assertTrue(vm.state.value.ticks.isEmpty())
    }

    @Test fun `an apply after the server lost the document's read reads it again`() = runTest {
        preview()
        val vm = vm(); vm.state.await { it.report != null }
        env.onResponses("POST", "/recipes/import", listOf(
            422 to """{"detail":"Read this document again before adding its recipes."}""",
            202 to fixture("import_read_running.json")))
        env.on("GET", "/imports/9", body = fixture("import_read_done.json"))
        vm.apply()
        val s = vm.state.await { it.report != null && !it.starting && env.count("GET", "/imports/9") > 0 }
        assertNull(s.applyError); assertNull(s.job); assertTrue(s.canApply)
        assertEquals(3, env.count("POST", "/recipes/import"))            // preview, the refused apply, the new read
    }
}
