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
import dev.mealprep.app.ui.camera.PageStore
import dev.mealprep.app.ui.camera.refPrompt
import dev.mealprep.app.ui.camera.resolveRefLine
import java.io.File
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
        const val RECIPE_ID = "recipe_id"; const val FOR_LINE = "for_line"; const val PAGE = "page"
        const val OUT_RECIPE_ID = "out_recipe_id"; const val OUT_ENTRY_ID = "out_entry_id"; const val OUT_TITLE = "out_title"
        const val OUT_EXISTING = "out_existing"; const val OUT_WEEK = "out_week"; const val OUT_RATING = "out_rating"
        const val OUT_MISSING_LINE = "out_missing_line"; const val OUT_MISSING_PAGE = "out_missing_page"
        const val OUT_MISSING_TEXT = "out_missing_text"
        const val ERROR = "error"
        /** On failure output: false when the server may have processed the request (timeout, odd reply), so
         *  "Try again" must warn instead of blindly re-sending. */
        const val RETRY_SAFE = "retry_safe"
        /** With exponential backoff from 1 min this waits ~8 h — "shared while out, imported when home". */
        const val MAX_ATTEMPTS = 10
        const val INTERRUPTED = "Sending the recipe was interrupted."

        const val PAGES_GONE = "The photos are gone from the phone. Take or share them again."
        const val PHOTO_UNREADABLE = "Couldn't read a recipe in those photos. Check that each whole page is in the " +
            "picture, sharp and well lit, then try again."
        const val PAGE_UNREADABLE = "Couldn't read that page. Check that the whole page is in the picture, sharp and " +
            "well lit, then try again."
        const val ALREADY_ATTACHED = "That page is already part of the recipe."

        /** The server's 502 on an import means it couldn't fetch or read the recipe. */
        fun importMessage(e: ApiError, kind: String? = LINK): String = when {
            e is ApiError.Http && e.code == 502 && kind == PHOTO -> PHOTO_UNREADABLE
            e is ApiError.Http && e.code == 502 && kind == PAGES -> PAGE_UNREADABLE
            alreadyAttached(e, kind) -> ALREADY_ATTACHED
            e is ApiError.Http && e.code == 502 -> "Couldn't read that recipe. Try again later."
            else -> e.userMessage()
        }

        /** POST /recipes/{id}/pages answers 409 "line N already has its sub-recipe attached" (nothing to retry). */
        private fun alreadyAttached(e: ApiError, kind: String?) =
            kind == PAGES && e is ApiError.Http && e.code == 409 && e.detail.orEmpty().contains("already")

        fun progressText(kind: String?): String = when (kind) {
            PHOTO -> "Reading cookbook pages…"
            PAGES -> "Reading the referenced page…"
            else -> "Reading recipe…"
        }
    }

    private val kind: String? get() = inputData.getString(KIND)

    override suspend fun doWork(): Result {
        // Best effort: Android refuses a foreground service if this attempt started while the app was in the background.
        try {
            setForeground(notifier.importProgress(id, progressText(kind)))
        } catch (e: CancellationException) {
            throw e   // java's CancellationException is an IllegalStateException: never swallow it below
        } catch (e: IllegalStateException) {
            // Refused (e.g. ForegroundServiceStartNotAllowedException is an IllegalStateException): carry on unannounced.
        }
        // Only jobs queued by 0.4.1 and earlier carry a week; since 0.4.2 imports go to the library only.
        val week = inputData.getString(WEEK)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when (kind) {
            LINK -> {
                val text = inputData.getString(TEXT)
                if (text == null) fail("The shared link was incomplete.", retrySafe = false)
                else send { importer.shareLink(text, week) }
            }
            PHOTO -> {
                val dir = inputData.getString(DIR)?.let(::File)
                val pages = dir?.let(PageStore::pagesIn).orEmpty()
                when {
                    dir == null -> fail("The photo import was incomplete.", retrySafe = false)
                    pages.isEmpty() -> fail(PAGES_GONE, retrySafe = false)
                    // The pages stay on the phone until the server has the recipe, so "Try again" can resend them.
                    else -> send(cleanup = dir) { importer.importPhotos(pages, week, inputData.getString(TITLE)) }
                }
            }
            PAGES -> {
                val dir = inputData.getString(DIR)?.let(::File)
                val pages = dir?.let(PageStore::pagesIn).orEmpty()
                val recipeId = inputData.getInt(RECIPE_ID, 0)
                val line = inputData.getInt(FOR_LINE, -1)
                when {
                    dir == null || recipeId <= 0 || line < 0 -> fail("The page to add was incomplete.", retrySafe = false)
                    pages.isEmpty() -> fail(PAGES_GONE, retrySafe = false)
                    else -> when (val now = importer.fetchRecipe(recipeId)) {
                        is ApiResult.Err -> retryOrFail(now.error)
                        is ApiResult.Ok -> when (val at = resolveRefLine(now.value, line, inputData.getInt(PAGE, 0))) {
                            null -> { dir.deleteRecursively(); fail(ALREADY_ATTACHED, retrySafe = false) }
                            else -> attach(dir, recipeId, pages, at)
                        }
                    }
                }
            }
            else -> fail("Unknown import type.")
        }
    }

    /** POST /recipes/{id}/pages (once): the server reads the page and adds its ingredients after [line]. */
    private suspend fun attach(dir: File, recipeId: Int, pages: List<File>, line: Int): Result =
        sendOnce(dir, { importer.attachPages(recipeId, pages, line) }) { recipe ->
            notifier.pagesAttached(recipe)
            val next = refPrompt(recipe)
            workDataOf(KIND to PAGES, OUT_RECIPE_ID to recipe.id, OUT_TITLE to recipe.title,
                OUT_MISSING_LINE to (next?.line ?: -1), OUT_MISSING_PAGE to (next?.page ?: 0), OUT_MISSING_TEXT to next?.raw)
        }

    /** Sends at most once per job: if WorkManager stopped an earlier run mid-request and runs the job again, the
     *  server may already have saved the recipe, so this run reports that instead of sending again. */
    private suspend fun send(cleanup: File? = null, call: suspend () -> ApiResult<ShareResult>): Result =
        sendOnce(cleanup, call) { s -> notifier.imported(s); shareOutput(s) }

    private suspend fun <T> sendOnce(cleanup: File?, call: suspend () -> ApiResult<T>, done: (T) -> Data): Result {
        if (!importer.markSending(id)) return fail(INTERRUPTED, retrySafe = false)
        val r = call()
        if (r is ApiResult.Err && r.error == ApiError.Unreachable) importer.clearSending(id)
        return when (r) {
            is ApiResult.Ok -> {
                cleanup?.deleteRecursively()   // the server has the pages now
                Result.success(done(r.value))
            }
            is ApiResult.Err -> retryOrFail(r.error)
        }
    }

    /** Only "couldn't connect" is retried: that request never reached the server. A timeout may have succeeded,
     *  and sending it again would add the recipe twice. */
    private fun retryOrFail(e: ApiError): Result =
        if (e == ApiError.Unreachable && runAttemptCount < MAX_ATTEMPTS - 1) Result.retry()
        else fail(importMessage(e, kind), retrySafe = e != ApiError.TimedOut && e !is ApiError.Other && !alreadyAttached(e, kind))

    private fun fail(message: String, retrySafe: Boolean = false): Result {
        notifier.importFailed(id, message, retrySafe, kind)
        return Result.failure(Data.Builder().putAll(inputData).putString(ERROR, message).putBoolean(RETRY_SAFE, retrySafe).build())
    }

    private fun shareOutput(s: ShareResult): Data {
        val missing = s.recipe.firstMissingRef()
        return workDataOf(
            OUT_RECIPE_ID to s.recipe.id, OUT_ENTRY_ID to (s.entry?.id ?: -1), OUT_TITLE to s.recipe.title,
            OUT_EXISTING to s.existing, OUT_WEEK to s.entry?.week, OUT_RATING to RatingText.summary(s.recipe.ratings),
            OUT_MISSING_LINE to (missing?.first ?: -1), OUT_MISSING_PAGE to (missing?.second ?: 0),
            OUT_MISSING_TEXT to missing?.let { s.recipe.ingredients[it.first].raw },
        )
    }
}
