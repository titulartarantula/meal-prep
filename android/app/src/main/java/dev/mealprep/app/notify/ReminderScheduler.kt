package dev.mealprep.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * One delayed WorkManager job per reminder (unique work `reminder-<id>`). Delays survive reboots and need no
 * exact-alarm permission; a few minutes' slack is fine for these.
 */
class ReminderScheduler(private val wm: WorkManager) {
    companion object {
        const val TAG = "reminder"
        private const val AT = "at:"
        /** A reminder that is due but hasn't run yet (Doze) is left to run for this long rather than cancelled. */
        val GRACE: Duration = Duration.ofHours(3)
        fun name(id: String) = "reminder-$id"
    }

    /**
     * Queues every reminder in [reminders] (each replaces the one queued under its id: the time or text may have
     * changed) and cancels the queued ones no longer wanted. Call from a background thread (it waits on WorkManager).
     */
    fun reconcile(reminders: List<Reminder>, now: ZonedDateTime) {
        val wanted = reminders.associateBy { name(it.id) }
        wanted.forEach { (name, r) ->
            val at = r.at.atZone(now.zone)
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInitialDelay(Duration.between(now, at).coerceAtLeast(Duration.ZERO))
                    .addTag(TAG).addTag(name).addTag(AT + at.toInstant().toEpochMilli())
                    .setInputData(ReminderWorker.input(r))
                    .build())
        }
        val overdue = now.toInstant().minus(GRACE).toEpochMilli()..now.toInstant().toEpochMilli()
        wm.getWorkInfosByTag(TAG).get()
            .filter { it.state == WorkInfo.State.ENQUEUED && it.tags.none { t -> t in wanted } }
            .filter { info -> info.tags.firstOrNull { it.startsWith(AT) }?.removePrefix(AT)?.toLongOrNull()?.let { it in overdue } != true }
            .forEach { wm.cancelWorkById(it.id) }
    }
}

/** Posts one reminder when it's due — unless [stillWanted] says the dinner was rated, moved or removed meanwhile. */
class ReminderWorker(
    ctx: Context,
    params: WorkerParameters,
    private val notifier: Notifier,
    private val stillWanted: suspend (Reminder) -> Boolean = { true },
) : CoroutineWorker(ctx, params) {
    companion object {
        const val ID = "id"; const val KIND = "kind"; const val AT = "at"; const val TITLE = "title"; const val TEXT = "text"
        const val NAV = "nav"; const val ENTRY = "entry"; const val WEEK = "week"

        fun input(r: Reminder): Data = workDataOf(ID to r.id, KIND to r.kind.name, AT to r.at.toString(), TITLE to r.title,
            TEXT to r.text, NAV to r.nav, ENTRY to r.entryId, WEEK to r.week?.toString())

        fun reminder(d: Data): Reminder? = runCatching {
            Reminder(d.getString(ID)!!, ReminderKind.valueOf(d.getString(KIND)!!), LocalDateTime.parse(d.getString(AT)),
                d.getString(TITLE).orEmpty(), d.getString(TEXT).orEmpty(), d.getString(NAV).orEmpty(),
                d.getInt(ENTRY, 0), d.getString(WEEK)?.let(LocalDate::parse))
        }.getOrNull()
    }

    override suspend fun doWork(): Result {
        val r = reminder(inputData) ?: return Result.failure()
        if (!runCatching { stillWanted(r) }.getOrDefault(true)) return Result.success()
        notifier.reminder(r.id, r.title, r.text, r.nav.ifEmpty { null })
        return Result.success()
    }
}
