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
import dev.mealprep.app.notify.Notifier
import java.time.LocalDate
import kotlinx.coroutines.delay

/** Polls a server job (cart draft or prep plan) until it leaves "building", then notifies — if the app isn't open. */
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
        const val KIND = "kind"; const val DRAFT = "draft"; const val PREP = "prep"
        const val ID = "id"; const val WEEK = "week"; const val POLL_MS = "poll_ms"; const val MAX_POLLS = "max_polls"
        const val OUT_STATUS = "status"
        /** 3 × ~9 min of polling (WorkManager stops a worker after 10 min). The prep plan takes ~4 min. */
        const val MAX_ATTEMPTS = 3
        const val GAVE_UP = "Still working after half an hour — open Meal Prep to check."
    }

    override suspend fun doWork(): Result {
        val kind = inputData.getString(KIND)?.takeIf { it == DRAFT || it == PREP } ?: return Result.failure()
        val id = inputData.getInt(ID, 0)
        val week = runCatching { LocalDate.parse(inputData.getString(WEEK)) }.getOrNull() ?: return Result.failure()
        val pollMs = inputData.getLong(POLL_MS, 10_000)
        repeat(inputData.getInt(MAX_POLLS, 54)) { i ->
            if (i > 0) delay(pollMs)
            when (val r = status(kind, id)) {
                is ApiResult.Ok -> if (r.value.first != "building") {
                    afterDone(kind)
                    announce(kind, id, week, r.value.first, r.value.second)
                    return Result.success(workDataOf(OUT_STATUS to r.value.first))
                }
                is ApiResult.Err -> when (r.error) {
                    ApiError.Unreachable, ApiError.TimedOut -> Unit          // brief Wi-Fi drop: keep polling
                    else -> return Result.failure()                          // 404, token, not configured
                }
            }
        }
        if (runAttemptCount < MAX_ATTEMPTS - 1) return Result.retry()
        if (repo.markOnce("$kind-done:$id") && !inForeground()) notifier.jobDone(kind, id, week, ok = false, error = GAVE_UP)
        return Result.failure()
    }

    private suspend fun status(kind: String, id: Int): ApiResult<Pair<String, String?>> = when (kind) {
        DRAFT -> when (val r = repo.draft(id)) { is ApiResult.Ok -> ApiResult.Ok(r.value.status to r.value.error); is ApiResult.Err -> r }
        else -> when (val r = repo.prepPlan(id)) { is ApiResult.Ok -> ApiResult.Ok(r.value.status to r.value.error); is ApiResult.Err -> r }
    }

    private suspend fun announce(kind: String, id: Int, week: LocalDate, status: String, error: String?) {
        if (!repo.markOnce("$kind-done:$id")) return     // already told (this worker before, or the hourly sync)
        if (inForeground()) return                       // she's looking at it
        notifier.jobDone(kind, id, week, ok = status == "ready" || status == "sent", error = error)
    }
}

class JobWatcher(private val wm: WorkManager) {
    fun watchDraft(id: Int, week: LocalDate) = watch(JobWatchWorker.DRAFT, id, week)
    fun watchPrep(id: Int, week: LocalDate) = watch(JobWatchWorker.PREP, id, week)

    private fun watch(kind: String, id: Int, week: LocalDate) {
        wm.enqueueUniqueWork("watch-$kind-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<JobWatchWorker>()
                .setInputData(workDataOf(JobWatchWorker.KIND to kind, JobWatchWorker.ID to id, JobWatchWorker.WEEK to week.toString()))
                .build())
    }
}
