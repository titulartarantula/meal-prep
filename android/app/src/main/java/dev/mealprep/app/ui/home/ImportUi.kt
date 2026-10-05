package dev.mealprep.app.ui.home

import androidx.work.Data
import androidx.work.WorkInfo
import dev.mealprep.app.ui.camera.RefPrompt
import dev.mealprep.app.work.ImportWorker
import java.time.LocalDate
import java.util.UUID

/** [runAttemptCount] > 0 while ENQUEUED = an earlier attempt couldn't connect and WorkManager is waiting to retry. */
data class ImportJob(
    val id: UUID, val state: WorkInfo.State, val output: Data, val runAttemptCount: Int = 0, val kind: String = ImportWorker.LINK,
)

sealed interface ImportUi {
    val id: UUID
    /** [kind] is ImportWorker.LINK / PHOTO / PAGES: photos take longer and say so. */
    data class Reading(override val id: UUID, val kind: String = ImportWorker.LINK) : ImportUi
    /** Couldn't reach the server (away from home): queued, sent automatically once it can connect. */
    data class Waiting(override val id: UUID) : ImportUi
    /** [retrySafe] false = the request may have reached the server (timed out), so "Try again" is not offered. */
    data class Failed(override val id: UUID, val message: String, val retrySafe: Boolean = false, val kind: String = ImportWorker.LINK) : ImportUi
    data class Done(
        override val id: UUID, val recipeId: Int, val title: String, val existing: Boolean, val ratingLine: String?,
        val missingLine: Int, val missingPage: Int, val week: LocalDate, val missingText: String? = null,
    ) : ImportUi {
        /** "Add a photo of p.191?" — the line whose page isn't attached, or null. */
        val ref: RefPrompt? get() = if (missingLine >= 0) RefPrompt(recipeId, missingLine, missingText ?: "page $missingPage", missingPage) else null
    }
    /** A referenced page was added to [title]; [next] is another page the recipe still needs, if any. */
    data class PageAdded(override val id: UUID, val recipeId: Int, val title: String, val next: RefPrompt? = null) : ImportUi
}

fun importUi(jobs: List<ImportJob>, hidden: Set<UUID>): List<ImportUi> = jobs.filter { it.id !in hidden }.mapNotNull { j ->
    val o = j.output
    when (j.state) {
        WorkInfo.State.ENQUEUED -> if (j.runAttemptCount > 0) ImportUi.Waiting(j.id) else ImportUi.Reading(j.id, j.kind)
        WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> ImportUi.Reading(j.id, j.kind)
        WorkInfo.State.FAILED -> ImportUi.Failed(j.id, o.getString(ImportWorker.ERROR) ?: "Couldn't add the recipe.",
            o.getBoolean(ImportWorker.RETRY_SAFE, false), o.getString(ImportWorker.KIND) ?: j.kind)
        WorkInfo.State.SUCCEEDED -> if (o.getString(ImportWorker.KIND) == ImportWorker.PAGES) {
            val id = o.getInt(ImportWorker.OUT_RECIPE_ID, 0)
            val line = o.getInt(ImportWorker.OUT_MISSING_LINE, -1)
            val page = o.getInt(ImportWorker.OUT_MISSING_PAGE, 0)
            ImportUi.PageAdded(j.id, id, o.getString(ImportWorker.OUT_TITLE) ?: "the recipe",
                if (line >= 0) RefPrompt(id, line, o.getString(ImportWorker.OUT_MISSING_TEXT) ?: "page $page", page) else null)
        } else o.getString(ImportWorker.OUT_WEEK)?.let { week ->
            ImportUi.Done(j.id, o.getInt(ImportWorker.OUT_RECIPE_ID, 0), o.getString(ImportWorker.OUT_TITLE) ?: "Recipe",
                o.getBoolean(ImportWorker.OUT_EXISTING, false), o.getString(ImportWorker.OUT_RATING),
                o.getInt(ImportWorker.OUT_MISSING_LINE, -1), o.getInt(ImportWorker.OUT_MISSING_PAGE, 0), LocalDate.parse(week),
                o.getString(ImportWorker.OUT_MISSING_TEXT))
        }
        WorkInfo.State.CANCELLED -> null
    }
}

/** What the home screen's card says while an import runs. */
fun readingText(kind: String): String = when (kind) {
    ImportWorker.PHOTO -> "Reading the cookbook pages… (about a minute)"
    ImportWorker.PAGES -> "Reading the referenced page…"
    else -> "Reading recipe…"
}

/** Where "Add photo of p.N" opens the camera. */
fun refRoute(p: RefPrompt) = dev.mealprep.app.ui.nav.CameraRoute("ref", p.recipeId, p.line, p.page)
