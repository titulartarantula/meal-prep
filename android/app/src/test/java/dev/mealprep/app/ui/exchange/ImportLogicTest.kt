package dev.mealprep.app.ui.exchange

import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.ImportItem
import dev.mealprep.app.data.api.ImportMatch
import dev.mealprep.app.data.api.ImportReport
import dev.mealprep.app.data.api.Progress
import dev.mealprep.app.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportLogicTest {
    private val preview = Http.json.decodeFromString(ImportReport.serializer(), fixture("import_preview.json"))
    private val done = Http.json.decodeFromString(ImportReport.serializer(), fixture("import_job_done.json"))
    private fun item(key: String) = preview.items.first { it.key == key }

    @Test fun `sections and what can be ticked`() {
        assertEquals(listOf(ImportSection.NEW, ImportSection.NEW, ImportSection.ALREADY, ImportSection.CHANGED, ImportSection.ALREADY,
            ImportSection.FAILED), preview.items.map(ImportLogic::section))
        assertEquals(listOf(true, true, false, true, true, false), preview.items.map(ImportLogic::selectable))
        assertEquals("update", ImportLogic.onAction(item("3:8c9d0e1f")))
        assertEquals("add", ImportLogic.onAction(item("4:2a3b4c5d")))
    }

    @Test fun `defaults follow the server, new ticked and the rest not`() {
        assertEquals(setOf("0:9f2c1a7e", "1:0b1c2d3e"), ImportLogic.defaultTicks(preview))
    }

    @Test fun `choices carry every readable item, ticked or skip`() {
        val ticks = setOf("0:9f2c1a7e", "3:8c9d0e1f", "4:2a3b4c5d", "2:4e5f6a7b")   // the plain duplicate can't be ticked
        assertEquals(mapOf("0:9f2c1a7e" to "add", "1:0b1c2d3e" to "skip", "2:4e5f6a7b" to "skip", "3:8c9d0e1f" to "update",
            "4:2a3b4c5d" to "add"), ImportLogic.choices(preview, ticks))
        assertEquals(2, ImportLogic.adds(preview, ticks)); assertEquals(1, ImportLogic.updates(preview, ticks))
    }

    @Test fun `button label follows the ticks`() {
        assertEquals("Add 12 recipes", ImportLogic.buttonLabel(12, 0))
        assertEquals("Add 1 recipe", ImportLogic.buttonLabel(1, 0))
        assertEquals("Update 1 recipe", ImportLogic.buttonLabel(0, 1))
        assertEquals("Add 12, update 1", ImportLogic.buttonLabel(12, 1))
        assertEquals("Nothing selected", ImportLogic.buttonLabel(0, 0))
    }

    @Test fun `summary, detail, match and tick words`() {
        assertEquals("2 new · 1 changed since exported · 2 already in Recipes · 1 couldn't be read", ImportLogic.summary(preview))
        assertEquals("New · 9 ingredients · 5 steps · Other · recipes.example.org", ImportLogic.detail(item("0:9f2c1a7e")))
        assertEquals("New · 6 ingredients · 3 steps · Book · The Imaginary Larder · 2 ratings", ImportLogic.detail(item("1:0b1c2d3e")))
        assertEquals("Couldn't read", ImportLogic.detail(item("5:6e7f8a9b")))
        assertEquals("Recipe 6 (no name)", ImportLogic.title(item("5:6e7f8a9b")))
        assertEquals("Same as “Test Bean Chili” in Recipes", ImportLogic.matchLine(item("2:4e5f6a7b")))
        assertEquals("Same name as “Test pancakes” in Recipes", ImportLogic.matchLine(item("4:2a3b4c5d")))
        assertEquals("Same as “Soup” earlier in this file",
            ImportLogic.matchLine(ImportItem("7:x", status = "duplicate", match = ImportMatch(null, "Soup", "id"))))
        assertNull(ImportLogic.matchLine(item("0:9f2c1a7e")))
        assertEquals("Replace with the file's version", ImportLogic.tickLabel(item("3:8c9d0e1f")))
        assertEquals("Add anyway", ImportLogic.tickLabel(item("4:2a3b4c5d")))
        assertNull(ImportLogic.tickLabel(item("0:9f2c1a7e")))
    }

    @Test fun `progress and result in words`() {
        val running = done.copy(status = "running", progress = Progress(2, 12))
        assertEquals("Adding 3 of 12…", ImportLogic.progress(running))
        assertEquals("Adding 12 of 12…", ImportLogic.progress(running.copy(progress = Progress(12, 12))))
        assertEquals("Starting…", ImportLogic.progress(running.copy(progress = Progress(0, 0))))
        assertTrue(ImportLogic.running(running)); assertFalse(ImportLogic.running(done))
        assertEquals("Added 2 recipes. Updated 1 recipe. 1 was already in Recipes. 1 couldn't be added.", ImportLogic.result(done))
        assertEquals("No recipes were added. 3 were already in Recipes.",
            ImportLogic.result(done.copy(counts = mapOf("duplicate" to 3))))
        assertEquals("Added 1 recipe.", ImportLogic.result(done.copy(counts = mapOf("added" to 1))))
        assertEquals("Recipes imported, with problems", ImportLogic.doneTitle(done))
        assertEquals("Recipes imported", ImportLogic.doneTitle(done.copy(counts = mapOf("added" to 1))))
    }

    @Test fun `a job the server cut off says what to do`() {
        val cut = done.copy(status = "failed", error = "interrupted by a server restart")
        assertEquals("Import stopped", ImportLogic.doneTitle(cut))
        assertTrue(ImportLogic.jobError(cut)!!.startsWith("The import stopped when the server restarted."))
        assertTrue(ImportLogic.jobError(cut.copy(error = "boom"))!!.contains("Import the file again"))
        assertNull(ImportLogic.jobError(done))
    }
}
