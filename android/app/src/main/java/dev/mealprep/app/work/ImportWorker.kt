package dev.mealprep.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.mealprep.app.core.RatingText
import dev.mealprep.app.core.firstMissingRef
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.ShareResult
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.notify.Notifier
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/** Sends a recipe to the server (NYT link, cookbook photos, or a referenced page) and reports the result. */
class ImportWorker(
    ctx: Context,
    params: WorkerParameters,
    private val importer: Importer,
    private val notifier: Notifier,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val KIND = "kind"; const val LINK = "link"; const val PHOTO = "photo"; const val PAGES = "pages"
        const val TEXT = "text"; const val WEEK = "week"; const val DIR = "dir"; const val TITLE = "title"
        const val RECIPE_ID = "recipe_id"; const val FOR_LINE = "for_line"
        const val OUT_RECIPE_ID = "out_recipe_id"; const val OUT_ENTRY_ID = "out_entry_id"; const val OUT_TITLE = "out_title"
        const val OUT_EXISTING = "out_existing"; const val OUT_WEEK = "out_week"; const val OUT_RATING = "out_rating"
        const val OUT_MISSING_LINE = "out_missing_line"; const val OUT_MISSING_PAGE = "out_missing_page"
        const val ERROR = "error"
        /** On failure output: false when the server may have processed the request (timeout, odd reply), so
         *  "Try again" must warn instead of blindly re-sending. */
        const val RETRY_SAFE = "retry_safe"
        /** With exponential backoff from 1 min this waits ~8 h — "shared while out, imported when home". */
        const val MAX_ATTEMPTS = 10
        const val INTERRUPTED = "Sending the recipe was interrupted."

        /** The server's 502 on an import means it couldn't fetch or read the recipe. */
        fun importMessage(e: ApiError): String =
            if (e is ApiError.Http && e.code == 502) "Couldn't read that recipe. Try again later." else e.userMessage()
    }

    override suspend fun doWork(): Result {
        // Best effort: Android refuses a foreground service if this attempt started while the app was in the background.
        try {
            setForeground(notifier.importProgress(id, "Reading recipe…"))
        } catch (e: CancellationException) {
            throw e   // java's CancellationException is an IllegalStateException: never swallow it below
        } catch (e: IllegalStateException) {
            // Refused (e.g. ForegroundServiceStartNotAllowedException is an IllegalStateException): carry on unannounced.
        }
        return when (inputData.getString(KIND)) {
            LINK -> {
                val text = inputData.getString(TEXT)
                val week = runCatching { LocalDate.parse(inputData.getString(WEEK)) }.getOrNull()
                if (text == null || week == null) fail("The shared link was incomplete.", retrySafe = false)
                else send { importer.shareLink(text, week) }
            }
            else -> fail("Unknown import type.")
        }
    }

    /** Sends at most once per job: if WorkManager stopped an earlier run mid-request and runs the job again, the
     *  server may already have added the recipe to the week, so this run reports that instead of sending again. */
    private suspend fun send(call: suspend () -> ApiResult<ShareResult>): Result {
        if (!importer.markSending(id)) return fail(INTERRUPTED, retrySafe = false)
        val r = call()
        if (r is ApiResult.Err && r.error == ApiError.Unreachable) importer.clearSending(id)
        return when (r) {
            is ApiResult.Ok -> { notifier.imported(r.value); Result.success(shareOutput(r.value)) }
            is ApiResult.Err -> retryOrFail(r.error)
        }
    }

    /** Only "couldn't connect" is retried: that request never reached the server. A timeout may have succeeded,
     *  and sending it again would add the recipe (or the week entry) twice. */
    private fun retryOrFail(e: ApiError): Result =
        if (e == ApiError.Unreachable && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry()
        else fail(importMessage(e), retrySafe = e != ApiError.TimedOut && e !is ApiError.Other)

    private fun fail(message: String, retrySafe: Boolean = false): Result {
        notifier.importFailed(id, message, retrySafe)
        return Result.failure(Data.Builder().putAll(inputData).putString(ERROR, message).putBoolean(RETRY_SAFE, retrySafe).build())
    }

    private fun shareOutput(s: ShareResult): Data {
        val missing = s.recipe.firstMissingRef()
        return workDataOf(
            OUT_RECIPE_ID to s.recipe.id, OUT_ENTRY_ID to s.entry.id, OUT_TITLE to s.recipe.title,
            OUT_EXISTING to s.existing, OUT_WEEK to s.entry.week, OUT_RATING to RatingText.summary(s.recipe.ratings),
            OUT_MISSING_LINE to (missing?.first ?: -1), OUT_MISSING_PAGE to (missing?.second ?: 0),
        )
    }
}
