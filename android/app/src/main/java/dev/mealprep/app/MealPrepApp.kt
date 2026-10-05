package dev.mealprep.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration

class MealPrepApp : Application(), Configuration.Provider {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.notifier.ensureChannels()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(graph.workerFactory()).build()
}

val Context.graph: AppGraph get() = (applicationContext as MealPrepApp).graph
