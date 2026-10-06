package dev.mealprep.app.ui.home

import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.mealprep.app.data.api.Checklist
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.work.ImportWorker
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLogicTest {
    private val wk = LocalDate.parse("2026-10-11")
    private val wed = LocalDate.parse("2026-10-07")                  // the week before: planning ahead
    private fun e(id: Int, day: Int?, title: String = "R$id") = PlanEntry(id, "2026-10-11", id, day, title = title)
    private val entries = listOf(e(21, 2, "Chili"), e(22, 4, "Fish soup"), e(23, null))
    private fun prep(status: String, done: Int, total: Int) = PrepPlan(4, status, checklist = Checklist(done = done, total = total))

    @Test fun `week view groups nights and the tray`() {
        val v = weekView(LocalDate.parse("2026-10-14"), entries)
        assertEquals(wk, v.week)
        assertEquals(7, v.nights.size)
        assertEquals(LocalDate.parse("2026-10-13"), v.nights[2].date)
        assertEquals(listOf(21), v.nights[2].entries.map { it.id })
        assertEquals(listOf(23), v.unplaced.map { it.id })
    }

    @Test fun `status strip`() {
        assertEquals(StatusStrip(true, true, true), statusStrip(entries, true, prep("ready", 4, 4)))
        assertEquals(StatusStrip(false, false, false), statusStrip(listOf(e(23, null)), false, prep("ready", 3, 4)))
        assertEquals(true, prepDone(prep("ready", 0, 0)))    // nothing to prep counts as done
        assertEquals(false, prepDone(prep("building", 0, 0)))
    }

    @Test fun `context action follows the week`() {
        assertEquals(ContextAction.AddRecipes, contextAction(wk, wed, emptyList(), false, null, null))
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, wed, entries, false, null, null))
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, wed, entries, false, Draft(7, "failed"), null))
        assertEquals(ContextAction.ReviewCart(7, true), contextAction(wk, wed, entries, false, Draft(7, "building"), null))
        assertEquals(ContextAction.ReviewCart(7, false), contextAction(wk, wed, entries, false, Draft(7, "ready"), null))
        assertEquals(ContextAction.StartPrep(wk), contextAction(wk, wed, entries, true, Draft(7, "sent"), null))
        assertEquals(ContextAction.StartPrep(wk), contextAction(wk, wed, entries, true, null, prep("failed", 0, 0)))
        assertEquals(ContextAction.ContinuePrep(wk, 0, 0, true), contextAction(wk, wed, entries, true, null, prep("building", 0, 0)))
        assertEquals(ContextAction.ContinuePrep(wk, 1, 4, false), contextAction(wk, wed, entries, true, null, prep("ready", 1, 4)))
        assertEquals(ContextAction.AllSet, contextAction(wk, wed, entries, true, null, prep("ready", 4, 4)))
        assertEquals(ContextAction.AllSet, contextAction(wk, wed, entries, true, null, prep("ready", 0, 0)))
    }

    @Test fun `a stale cart is an older cart - the week goes back to Build cart`() {
        // The server says carted = false once the week's recipes changed after the cart was sent.
        assertEquals(StatusStrip(planned = true, cartSent = false, prepDone = false), statusStrip(entries, false, null))
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, wed, entries, false, Draft(7, "sent", stale = true), null))
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, wed, entries, false, Draft(7, "ready", stale = true), null))
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, wed, entries, false, Draft(7, "building", stale = true), null))
        assertEquals(ContextAction.ReviewCart(8, false), contextAction(wk, wed, entries, false, Draft(8, "ready"), null))  // a new one
    }

    @Test fun `tonight wins once the prep Sunday has passed`() {
        val tue = LocalDate.parse("2026-10-13")
        assertEquals(ContextAction.Tonight(21, "Chili"), contextAction(wk, tue, entries, false, null, null))
        val sundayEntry = listOf(e(30, 0, "Roast"))
        assertEquals(ContextAction.StartPrep(wk), contextAction(wk, wk, sundayEntry, true, null, null))         // Sunday = prep day
        assertEquals(ContextAction.Tonight(30, "Roast"), contextAction(wk, wk, sundayEntry, true, null, prep("ready", 2, 2)))
        assertEquals(ContextAction.AllSet, contextAction(wk, LocalDate.parse("2026-10-20"), entries, false, null, null)) // week over
    }

    @Test fun `import cards`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID(); val d = UUID.randomUUID()
        val done = workDataOf(ImportWorker.OUT_RECIPE_ID to 3, ImportWorker.OUT_TITLE to "Wings", ImportWorker.OUT_EXISTING to true,
            ImportWorker.OUT_RATING to "Family 5/5", ImportWorker.OUT_MISSING_LINE to 1, ImportWorker.OUT_MISSING_PAGE to 191,
            ImportWorker.OUT_WEEK to "2026-10-11")
        val ui = importUi(listOf(
            ImportJob(a, WorkInfo.State.RUNNING, workDataOf()),
            ImportJob(b, WorkInfo.State.FAILED, workDataOf(ImportWorker.ERROR to "boom")),
            ImportJob(c, WorkInfo.State.SUCCEEDED, done),
            ImportJob(d, WorkInfo.State.SUCCEEDED, done),
        ), hidden = setOf(d))
        assertEquals(listOf(
            ImportUi.Reading(a),
            ImportUi.Failed(b, "boom", retrySafe = false),   // no RETRY_SAFE key: not safe
            ImportUi.Done(c, 3, "Wings", true, "Family 5/5", 1, 191, wk),
        ), ui)
    }

    @Test fun `a recipe saved to the library only has no week`() {
        val c = UUID.randomUUID()
        val out = workDataOf(ImportWorker.OUT_RECIPE_ID to 4, ImportWorker.OUT_TITLE to "Lentil Soup", ImportWorker.OUT_ENTRY_ID to -1,
            ImportWorker.OUT_MISSING_LINE to -1)
        val done = importUi(listOf(ImportJob(c, WorkInfo.State.SUCCEEDED, out)), emptySet()).single() as ImportUi.Done
        assertEquals(ImportUi.Done(c, 4, "Lentil Soup", false, null, -1, 0, null), done)
        assertEquals("Added Lentil Soup to Recipes", doneTitle(done.title, done.existing))
        assertEquals("Already in your Recipes: Lentil Soup", doneTitle(done.title, true))
    }

    @Test fun `an import waiting in retry backoff says so instead of spinning`() {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        val ui = importUi(listOf(
            ImportJob(a, WorkInfo.State.ENQUEUED, workDataOf(), runAttemptCount = 0),   // just queued: about to run
            ImportJob(b, WorkInfo.State.ENQUEUED, workDataOf(), runAttemptCount = 2),   // couldn't connect, waiting
            ImportJob(c, WorkInfo.State.RUNNING, workDataOf(), runAttemptCount = 2),    // trying again now
        ), hidden = emptySet())
        assertEquals(listOf(ImportUi.Reading(a), ImportUi.Waiting(b), ImportUi.Reading(c)), ui)
    }

    @Test fun `notification permission is asked once, and not when already granted`() {
        assertEquals(true, shouldAskForNotifications(granted = false, alreadyAsked = false))
        assertEquals(false, shouldAskForNotifications(granted = false, alreadyAsked = true))   // asked before: never again
        assertEquals(false, shouldAskForNotifications(granted = true, alreadyAsked = false))
    }

    @Test fun `a running photo import says it takes longer`() {
        val a = UUID.randomUUID()
        val ui = importUi(listOf(ImportJob(a, WorkInfo.State.RUNNING, workDataOf(), kind = ImportWorker.PHOTO)), emptySet())
        assertEquals(listOf(ImportUi.Reading(a, ImportWorker.PHOTO)), ui)
        assertEquals("Reading the cookbook pages… (about a minute)", readingText(ImportWorker.PHOTO))
        assertEquals("Reading recipe…", readingText(ImportWorker.LINK))
        assertEquals(ImportWorker.PHOTO, dev.mealprep.app.work.ImportQueue.kindOf(setOf("import", "import-kind:photo")))
        assertEquals(ImportWorker.LINK, dev.mealprep.app.work.ImportQueue.kindOf(setOf("import")))   // jobs from before tags
    }

    @Test fun `attached page shows its own card, with the next page if any`() {
        val id = UUID.randomUUID(); val id2 = UUID.randomUUID()
        assertEquals(listOf(
            ImportUi.PageAdded(id, 3, "Crêpes", null),
            ImportUi.PageAdded(id2, 3, "Crêpes", dev.mealprep.app.ui.camera.RefPrompt(3, 4, "Sauce, page 51", 51)),
        ), importUi(listOf(
            ImportJob(id, WorkInfo.State.SUCCEEDED, workDataOf(ImportWorker.KIND to ImportWorker.PAGES, ImportWorker.OUT_RECIPE_ID to 3,
                ImportWorker.OUT_TITLE to "Crêpes", ImportWorker.OUT_MISSING_LINE to -1)),
            ImportJob(id2, WorkInfo.State.SUCCEEDED, workDataOf(ImportWorker.KIND to ImportWorker.PAGES, ImportWorker.OUT_RECIPE_ID to 3,
                ImportWorker.OUT_TITLE to "Crêpes", ImportWorker.OUT_MISSING_LINE to 4, ImportWorker.OUT_MISSING_PAGE to 51,
                ImportWorker.OUT_MISSING_TEXT to "Sauce, page 51")),
        ), emptySet()))
    }

    @Test fun `an imported recipe with a page reference offers the photo`() {
        val done = ImportUi.Done(UUID.randomUUID(), 3, "Wings", false, null, 1, 191, wk, "Batter for 24 crêpes, page 191")
        assertEquals(dev.mealprep.app.ui.camera.RefPrompt(3, 1, "Batter for 24 crêpes, page 191", 191), done.ref)
        assertEquals(dev.mealprep.app.ui.nav.CameraRoute("ref", 3, 1, 191), refRoute(done.ref!!))
        assertEquals(null, done.copy(missingLine = -1).ref)
    }
}
