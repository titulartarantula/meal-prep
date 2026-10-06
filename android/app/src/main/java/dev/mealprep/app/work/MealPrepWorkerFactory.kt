package dev.mealprep.app.work

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.mealprep.app.AppGraph
import dev.mealprep.app.notify.ReminderWorker
import dev.mealprep.app.notify.Reminders

/** Builds workers with their dependencies. Each worker task adds its line. */
class MealPrepWorkerFactory(private val g: AppGraph) : WorkerFactory() {
    override fun createWorker(ctx: Context, name: String, params: WorkerParameters): ListenableWorker? = when (name) {
        ImportWorker::class.java.name -> ImportWorker(ctx, params, g.repo, g.notifier)
        JobWatchWorker::class.java.name -> JobWatchWorker(ctx, params, g.repo, g.notifier, ::inForeground,
            afterDone = { kind -> if (kind == JobWatchWorker.PREP) SyncWorker.now(g.workManager) })
        ReminderWorker::class.java.name -> ReminderWorker(ctx, params, g.notifier) { r -> Reminders.stillWanted(r, g.repo, g.settings.value.notif) }
        SyncWorker::class.java.name -> SyncWorker(ctx, params, g.repo, { g.settings.value }, g.reminders, g.notifier, ::inForeground)
        StaplesReminderWorker::class.java.name -> StaplesReminderWorker(ctx, params, g.notifier, g.workManager, prefs = { g.settings.value.notif })
        else -> null
    }

    private fun inForeground() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
