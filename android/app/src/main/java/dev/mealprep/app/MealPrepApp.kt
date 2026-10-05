package dev.mealprep.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import dev.mealprep.app.work.StaplesReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PAGES_KEPT_MS = 14L * 24 * 3600 * 1000

class MealPrepApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifier.ensureChannels()
        // Pages of imports abandoned long ago (a failed import nobody retried, an app killed mid-capture).
        graph.scope.launch(Dispatchers.IO) { graph.pages.pruneOlderThan(System.currentTimeMillis() - PAGES_KEPT_MS) }
        // The weekly staples reminder, if chosen, stays queued (KEEP) — re-queued only if it was lost.
        graph.settings.value.notif.takeIf { it.staples }?.let { StaplesReminder.apply(graph.workManager, it, policy = ExistingWorkPolicy.KEEP) }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(graph.workerFactory()).build()
}

val Context.graph: AppGraph get() = (applicationContext as MealPrepApp).graph
