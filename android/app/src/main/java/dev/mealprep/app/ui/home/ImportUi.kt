package dev.mealprep.app.ui.home

import androidx.work.Data
import androidx.work.WorkInfo
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
    data class Failed(override val id: UUID, val message: String, val retrySafe: Boolean = false) : ImportUi
    data class Done(
        override val id: UUID, val recipeId: Int, val title: String, val existing: Boolean, val ratingLine: String?,
        val missingLine: Int, val missingPage: Int, val week: LocalDate,
    ) : ImportUi
}

fun importUi(jobs: List<ImportJob>, hidden: Set<UUID>): List<ImportUi> = jobs.filter { it.id !in hidden }.mapNotNull { j ->
    val o = j.output
    when (j.state) {
        WorkInfo.State.ENQUEUED -> if (j.runAttemptCount > 0) ImportUi.Waiting(j.id) else ImportUi.Reading(j.id, j.kind)
        WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> ImportUi.Reading(j.id, j.kind)
        WorkInfo.State.FAILED -> ImportUi.Failed(j.id, o.getString(ImportWorker.ERROR) ?: "Couldn't add the recipe.", o.getBoolean(ImportWorker.RETRY_SAFE, false))
        WorkInfo.State.SUCCEEDED -> o.getString(ImportWorker.OUT_WEEK)?.let { week ->
            ImportUi.Done(j.id, o.getInt(ImportWorker.OUT_RECIPE_ID, 0), o.getString(ImportWorker.OUT_TITLE) ?: "Recipe",
                o.getBoolean(ImportWorker.OUT_EXISTING, false), o.getString(ImportWorker.OUT_RATING),
                o.getInt(ImportWorker.OUT_MISSING_LINE, -1), o.getInt(ImportWorker.OUT_MISSING_PAGE, 0), LocalDate.parse(week))
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
