package dev.mealprep.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.ImportReport
import dev.mealprep.app.notify.Notifier
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Polls a server job (cart draft, prep plan, or a recipe import) until it leaves "building" / "running", then
 *  notifies — if the app isn't open. An import's progress is published for the Recipes tab's card, and each poll
 *  keeps the job's copy for offline (the result stays readable away from home). */
class JobWatchWorker(
    ctx: Context,
    params: WorkerParameters,
    private val repo: Repository,
    private val notifier: Notifier,
    private val inForeground: () -> Boolean,
    /** A job left "building" (a ready prep plan brings thaw reminders: the phone's reminders are re-planned). */
    private val afterDone: (kind: String) -> Unit = {},
) : CoroutineWorker(ctx, params) {

    companion object {
        const val KIND = "kind"; const val DRAFT = "draft"; const val PREP = "prep"; const val IMPORT = "import"
        const val ID = "id"; const val WEEK = "week"; const val POLL_MS = "poll_ms"; const val MAX_POLLS = "max_polls"
        const val OUT_STATUS = "status"
        /** 3 × ~9 min of polling (WorkManager stops a worker after 10 min). The prep plan takes ~4 min. */
        const val MAX_ATTEMPTS = 3
        const val GAVE_UP = "Still working after half an hour — open Meal Prep to check."
        /** A big library takes a while (the AI tidies ~2 recipes a minute per worker): up to about an hour. */
        const val IMPORT_ATTEMPTS = 6
        const val IMPORT_GAVE_UP = "Still importing after an hour — open Meal Prep to check."
        /** Progress of an import (setProgress), for the Recipes tab. */
        const val DONE = "done"; const val TOTAL = "total"
    }

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KIND)?.takeIf { it == DRAFT || it == PREP || it == IMPORT } ?: return Result.failure()
        val id = inputData.getInt(ID, 0)
        val week = runCatching { LocalDate.parse(inputData.getString(WEEK)) }.getOrNull()
        if (week == null && kind != IMPORT) return Result.failure()
        val pollMs = inputData.getLong(POLL_MS, 10_000)
        repeat(inputData.getInt(MAX_POLLS, 54)) { i ->
            if (i > 0) delay(pollMs)
            when (val r = status(kind, id)) {
                is ApiResult.Ok -> if (r.value.first != "building" && r.value.first != "running") {
                    afterDone(kind)
                    if (kind == IMPORT) announceImport(id) else announce(kind, id, week!!, r.value.first, r.value.second)
                    return Result.success(workDataOf(OUT_STATUS to r.value.first))
                }
                is ApiResult.Err -> when (r.error) {
                    ApiError.Unreachable, ApiError.TimedOut -> Unit          // brief Wi-Fi drop: keep polling
                    else -> return Result.failure()                          // 404, token, not configured
                }
            }
        }
        if (runAttemptCount < (if (kind == IMPORT) IMPORT_ATTEMPTS else MAX_ATTEMPTS) - 1) return Result.retry()
        if (repo.markOnce("$kind-done:$id") && !inForeground()) {
            if (kind == IMPORT) notifier.importGaveUp(id, IMPORT_GAVE_UP) else notifier.jobDone(kind, id, week!!, ok = false, error = GAVE_UP)
        }
        return Result.failure()
    }

    private var lastImport: ImportReport? = null

    private suspend fun status(kind: String, id: Int): ApiResult<Pair<String, String?>> = when (kind) {
        IMPORT -> {
            val l = repo.importJob(id)
            val job = l.value
            if (l.error != null || job == null) ApiResult.Err(l.error ?: ApiError.Other("no job"))
            else {
                lastImport = job
                setProgress(workDataOf(DONE to job.progress.done, TOTAL to job.progress.total))
                ApiResult.Ok((job.status ?: "running") to job.error)
            }
        }
        DRAFT -> when (val r = repo.draft(id)) { is ApiResult.Ok -> ApiResult.Ok(r.value.status to r.value.error); is ApiResult.Err -> r }
        else -> when (val r = repo.prepPlan(id)) { is ApiResult.Ok -> ApiResult.Ok(r.value.status to r.value.error); is ApiResult.Err -> r }
    }

    private suspend fun announceImport(id: Int) {
        if (!repo.markOnce("$IMPORT-done:$id")) return
        if (inForeground()) return                       // the import screen or the Recipes card shows it
        lastImport?.let { notifier.importDone(id, it) }
    }

    private suspend fun announce(kind: String, id: Int, week: LocalDate, status: String, error: String?) {
        if (!repo.markOnce("$kind-done:$id")) return     // already told (this worker before, or the hourly sync)
        if (inForeground()) return                       // she's looking at it
        notifier.jobDone(kind, id, week, ok = status == "ready" || status == "sent", error = error)
    }
}

class JobWatcher(private val wm: WorkManager) {
    companion object {
        const val IMPORT_TAG = "watch-import"
        const val IMPORT_JOB_TAG = "import-job:"
    }

    fun watchDraft(id: Int, week: LocalDate) = watch(JobWatchWorker.DRAFT, id, week)
    fun watchPrep(id: Int, week: LocalDate) = watch(JobWatchWorker.PREP, id, week)

    /** A recipe import: polled every 5 s (progress for the Recipes tab), a notification when done if the app is closed. */
    fun watchImport(id: Int) {
        wm.enqueueUniqueWork("watch-${JobWatchWorker.IMPORT}-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JobWatchWorker>()
                .setInputData(workDataOf(JobWatchWorker.KIND to JobWatchWorker.IMPORT, JobWatchWorker.ID to id,
                    JobWatchWorker.POLL_MS to 5_000L, JobWatchWorker.MAX_POLLS to 108))
                .addTag(IMPORT_TAG).addTag(IMPORT_JOB_TAG + id)
                .build())
    }

    /** Imports being watched, for the Recipes tab's card. */
    val imports: Flow<List<WatchedImport>> get() = wm.getWorkInfosByTagFlow(IMPORT_TAG).map(::watchedImports)

    private fun watch(kind: String, id: Int, week: LocalDate) {
        wm.enqueueUniqueWork("watch-$kind-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JobWatchWorker>()
                .setInputData(workDataOf(JobWatchWorker.KIND to kind, JobWatchWorker.ID to id, JobWatchWorker.WEEK to week.toString()))
                .build())
    }
}

/** An import job the phone is watching: [running] until the worker finished; [done] of [total] recipes so far. */
data class WatchedImport(val jobId: Int, val running: Boolean, val done: Int = 0, val total: Int = 0)

fun watchedImports(infos: List<androidx.work.WorkInfo>): List<WatchedImport> = infos.mapNotNull { w ->
    val id = w.tags.firstOrNull { it.startsWith(JobWatcher.IMPORT_JOB_TAG) }?.removePrefix(JobWatcher.IMPORT_JOB_TAG)?.toIntOrNull()
        ?: return@mapNotNull null
    WatchedImport(id, !w.state.isFinished, w.progress.getInt(JobWatchWorker.DONE, 0), w.progress.getInt(JobWatchWorker.TOTAL, 0))
}
