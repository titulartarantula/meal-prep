package dev.mealprep.app.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

class ImportQueue(private val wm: WorkManager) {
    companion object {
        const val TAG = "import"
        fun linkInput(text: String, week: LocalDate): Data =
            workDataOf(ImportWorker.KIND to ImportWorker.LINK, ImportWorker.TEXT to text, ImportWorker.WEEK to week.toString())
    }

    fun enqueueLink(text: String, week: LocalDate): UUID = enqueue(linkInput(text, week))

    fun enqueue(input: Data): UUID {
        val req = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(input).addTag(TAG)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        wm.enqueue(req)
        return req.id
    }

    /** "Try again" on a failed import: its failure output echoes the original input. */
    fun retry(failedOutput: Data): UUID =
        enqueue(Data.Builder().putAll(failedOutput.keyValueMap.filterKeys { it != ImportWorker.ERROR && it != ImportWorker.RETRY_SAFE }).build())

    fun cancel(id: UUID) { wm.cancelWorkById(id) }

    val recent: Flow<List<WorkInfo>> = wm.getWorkInfosByTagFlow(TAG)
}
