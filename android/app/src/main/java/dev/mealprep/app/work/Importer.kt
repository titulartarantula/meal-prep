package dev.mealprep.app.work

import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.ShareResult
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** What the import worker needs from the server (Repository implements it). */
interface Importer {
    suspend fun shareLink(text: String, week: LocalDate): ApiResult<ShareResult>
    suspend fun importPhotos(pages: List<File>, week: LocalDate, title: String?): ApiResult<ShareResult>
    suspend fun attachPages(recipeId: Int, pages: List<File>, forLine: Int): ApiResult<Recipe>

    /** Durably notes that job [workId] is about to send its request. False if a previous run already did:
     *  that run was interrupted and the server may have processed it. */
    suspend fun markSending(workId: UUID): Boolean
    /** The request never reached the server (couldn't connect): the next run may send it again. */
    suspend fun clearSending(workId: UUID)
}
