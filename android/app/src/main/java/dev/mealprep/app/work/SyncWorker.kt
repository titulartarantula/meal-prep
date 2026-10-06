package dev.mealprep.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.Loaded
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.notify.Notifier
import dev.mealprep.app.notify.PlannedReminder
import dev.mealprep.app.notify.ReminderScheduler
import dev.mealprep.app.notify.Reminders
import dev.mealprep.app.notify.WeekData
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * Hourly, on app start and after a change on this phone (a dinner placed, moved, removed or rated; a prep plan
 * written; notification settings): refreshes the saved copies of last / this / next week, their prep plans' cook
 * cards and the library; tells this phone about a cart that is ready to review (built on either phone); and
 * re-plans this phone's reminders. Away from home it plans from the saved copies.
 */
class SyncWorker(
    ctx: Context,
    params: WorkerParameters,
    private val repo: Repository,
    private val settings: () -> Settings,
    private val scheduler: ReminderScheduler,
    private val notifier: Notifier,
    private val inForeground: () -> Boolean = { false },
    private val now: () -> ZonedDateTime = ZonedDateTime::now,
) : CoroutineWorker(ctx, params) {

    companion object {
        const val PERIODIC = "sync"
        const val NOW = "sync-now"
        /** Planned reminders saved for Settings ("Coming up on this phone"). */
        const val PLANNED = "reminders"
        val plannedSerializer = ListSerializer(PlannedReminder.serializer())

        fun schedule(wm: WorkManager) = wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build())

        /** A run now; a newer change replaces a run still in progress (it would plan from older data). */
        fun now(wm: WorkManager) = wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }

    override suspend fun doWork(): Result {
        val s = settings()
        if (!s.configured) return Result.success()
        val t = now()
        val cur = Weeks.weekStart(t.toLocalDate())
        val weeks = listOf(cur.minusWeeks(1), cur, cur.plusWeeks(1)).map { w -> Triple(w, repo.week(w), repo.weekPrepPlan(w)) }
        val live = weeks.all { (_, e, p) -> e.error == null && p.error == null }
        if (live) {
            weeks.forEach { (_, _, p) -> p.value?.let { repo.saveCards(it) } }   // cook cards readable away from home
            repo.recipes()
            if (s.notif.cartReady) cartReady(t)
        }
        // A week that couldn't be read and has no saved copy: keep the reminders already queued rather than drop them.
        fun unknown(l: Loaded<*>) = l.value == null && l.error != null && l.fetchedAt == null
        if (weeks.none { (_, e, p) -> unknown(e) || unknown(p) }) {
            val planned = Reminders.plan(weeks.map { (w, e, p) -> WeekData(w, e.value.orEmpty(), p.value) }, s.notif, t.toLocalDateTime())
            withContext(Dispatchers.IO) { scheduler.reconcile(planned, t) }
            repo.putLocal(PLANNED, plannedSerializer, planned.map { PlannedReminder(it.id, it.kind.name, it.at.toString(), it.title, it.text, it.nav) })
        }
        repo.prune(Duration.ofDays(60))
        return Result.success()
    }

    /** A cart draft for this week or a later one in the horizon that is ready to review: said once per draft, on
     *  each phone (the same key as JobWatchWorker, so the phone that built it isn't told twice). Not while the app
     *  is open: the home screen shows it, and a later sync says it if it is still waiting. */
    private suspend fun cartReady(t: ZonedDateTime) {
        if (inForeground()) return
        for (w in Weeks.horizon(t.toLocalDate())) {
            val d = repo.weekDraft(w)
            val draft = d.value ?: continue
            if (d.error == null && draft.status == "ready" && repo.markOnce("${JobWatchWorker.DRAFT}-done:${draft.id}"))
                notifier.jobDone(JobWatchWorker.DRAFT, draft.id, w, ok = true, error = null)
        }
    }
}
