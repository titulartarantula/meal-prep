package dev.mealprep.app.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.mealprep.app.AppGraph

/** Builds workers with their dependencies. Each worker task adds its line. */
class MealPrepWorkerFactory(private val g: AppGraph) : WorkerFactory() {
    override fun createWorker(ctx: Context, name: String, params: WorkerParameters): ListenableWorker? = when (name) {
        ImportWorker::class.java.name -> ImportWorker(ctx, params, g.repo, g.notifier)
        else -> null
    }
}
