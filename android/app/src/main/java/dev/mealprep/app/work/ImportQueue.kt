package dev.mealprep.app.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

class ImportQueue(private val wm: WorkManager) {
    companion object {
        const val TAG = "import"
        /** Fridge photo uploads (0.14.0): their own tag, so the recipe import cards never show them. */
        const val FRIDGE_TAG = "fridge"
        /** Tags each job with its kind, so the home screen can say what is being read while it runs. */
        const val KIND_TAG = "import-kind:"
        fun kindOf(tags: Set<String>): String =
            tags.firstOrNull { it.startsWith(KIND_TAG) }?.removePrefix(KIND_TAG) ?: ImportWorker.LINK

        /** An NYT link → the library ([week] null: the app no longer plans while importing; kept for old jobs). */
        fun linkInput(text: String, week: LocalDate? = null): Data =
            workDataOf(ImportWorker.KIND to ImportWorker.LINK, ImportWorker.TEXT to text, ImportWorker.WEEK to week?.toString())
        /** Cookbook pages in [dir] (page01.jpg …, in reading order) → a new library recipe from [book], [page]
         *  (+ the book's [author] / [isbn] when a suggestion was picked). [sourceKind] "other": [book] / [page] are
         *  another source's name and note (null = a book, as jobs queued before 0.7.1). */
        fun photoInput(dir: File, title: String?, book: String? = null, page: String? = null, week: LocalDate? = null,
                       author: String? = null, isbn: String? = null, sourceKind: String? = null): Data = workDataOf(
            ImportWorker.KIND to ImportWorker.PHOTO, ImportWorker.DIR to dir.path, ImportWorker.WEEK to week?.toString(),
            ImportWorker.TITLE to title, ImportWorker.BOOK to book, ImportWorker.BOOK_PAGE to page,
            ImportWorker.BOOK_AUTHOR to author, ImportWorker.BOOK_ISBN to isbn, ImportWorker.SOURCE_KIND to sourceKind)
        /** A photo of the page that ingredient line [forLine] of recipe [recipeId] refers to ("…, page 191"). */
        /** Fridge photos in [dir] for a read (POST /fridge/photos). */
        fun fridgeInput(dir: File): Data = workDataOf(ImportWorker.KIND to ImportWorker.FRIDGE, ImportWorker.DIR to dir.path)
        fun pagesInput(dir: File, recipeId: Int, forLine: Int, page: Int = 0): Data = workDataOf(
            ImportWorker.KIND to ImportWorker.PAGES, ImportWorker.DIR to dir.path,
            ImportWorker.RECIPE_ID to recipeId, ImportWorker.FOR_LINE to forLine, ImportWorker.PAGE to page)
    }

    fun enqueueLink(text: String): UUID = enqueue(linkInput(text))
    /** Fridge photos in [dir] (page01.jpg …) up for a read; the folder stays until the review is saved or discarded. */
    fun enqueueFridge(dir: File): UUID = enqueue(fridgeInput(dir), FRIDGE_TAG)
    fun enqueuePhotos(dir: File, title: String?, book: String?, page: String?, author: String? = null, isbn: String? = null,
                      sourceKind: String? = null): UUID =
        enqueue(photoInput(dir, title, book, page, author = author, isbn = isbn, sourceKind = sourceKind))
    fun enqueuePages(dir: File, recipeId: Int, forLine: Int, page: Int): UUID = enqueue(pagesInput(dir, recipeId, forLine, page))

    fun enqueue(input: Data, tag: String = TAG): UUID {
        val req = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(input).addTag(tag).addTag(KIND_TAG + (input.getString(ImportWorker.KIND) ?: ImportWorker.LINK))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueue(req)
        return req.id
    }

    /** "Try again" on a failed import: its failure output echoes the original input. */
    fun retry(failedOutput: Data): UUID {
        val input = Data.Builder().putAll(failedOutput.keyValueMap.filterKeys { it != ImportWorker.ERROR && it != ImportWorker.RETRY_SAFE }).build()
        return enqueue(input, if (input.getString(ImportWorker.KIND) == ImportWorker.FRIDGE) FRIDGE_TAG else TAG)
    }

    fun cancel(id: UUID) { wm.cancelWorkById(id) }

    val recent: Flow<List<WorkInfo>> = wm.getWorkInfosByTagFlow(TAG)
    /** Fridge photo uploads, for the Fridge screen's "Sending your fridge photos…". */
    val fridge: Flow<List<WorkInfo>> get() = wm.getWorkInfosByTagFlow(FRIDGE_TAG)
}
