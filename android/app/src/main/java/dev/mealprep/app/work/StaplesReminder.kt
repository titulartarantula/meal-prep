package dev.mealprep.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.notify.Notifier
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** The optional weekly "Time to check the staples" notification, scheduled on this phone (no server push). One
 *  delayed WorkManager job at a time; each run posts the notification and queues the next week's. */
object StaplesReminder {
    const val WORK = "staples-reminder"

    /** The next [day] at [at] strictly after [now] (local time; a DST change keeps the wall-clock time). */
    fun next(now: ZonedDateTime, day: DayOfWeek, at: LocalTime): ZonedDateTime {
        val t = now.with(TemporalAdjusters.nextOrSame(day)).with(at).withSecond(0).withNano(0)
        return if (t.isAfter(now)) t else t.plusWeeks(1)
    }

    /** Settings changed (REPLACE) or the app started (KEEP: an already queued reminder stays as it is). */
    fun apply(wm: WorkManager, prefs: NotifPrefs, now: ZonedDateTime = ZonedDateTime.now(),
              policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        if (!prefs.staples) { wm.cancelUniqueWork(WORK); return }
        wm.enqueueUniqueWork(WORK, policy, request(prefs, now))
    }

    internal fun request(prefs: NotifPrefs, now: ZonedDateTime) = OneTimeWorkRequestBuilder<StaplesReminderWorker>()
        .setInitialDelay(Duration.between(now, next(now, prefs.staplesDay, prefs.staplesAt)))
        .addTag(WORK)
        .build()
}

class StaplesReminderWorker(
    ctx: Context,
    params: WorkerParameters,
    private val notifier: Notifier,
    private val wm: WorkManager,
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
    private val prefs: () -> NotifPrefs,
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val p = prefs()
        if (!p.staples) return Result.success()            // turned off meanwhile: nothing more to queue
        notifier.staplesReminder()
        // Next week's runs after this one finishes; its delay counts from then (APPEND), so it isn't cancelled
        // the way REPLACE would cancel this running job.
        wm.enqueueUniqueWork(StaplesReminder.WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, StaplesReminder.request(p, now().plusSeconds(1)))
        return Result.success()
    }
}
