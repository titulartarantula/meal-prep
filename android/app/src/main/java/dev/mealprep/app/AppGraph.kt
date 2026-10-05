package dev.mealprep.app

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.SettingsApiProvider
import dev.mealprep.app.data.cache.CacheDb
import dev.mealprep.app.data.settings.SettingsStore
import dev.mealprep.app.work.MealPrepWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/** Hand-wired dependencies (one per process). */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsStore = SettingsStore.create(context)
    // First value read synchronously so the API provider never sees "not configured" during startup.
    val settings = settingsStore.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { settingsStore.settings.first() })
    val db: CacheDb = Room.databaseBuilder(context, CacheDb::class.java, "cache.db")
        .fallbackToDestructiveMigration(dropAllTables = true).build()
    val repo = Repository(SettingsApiProvider(settings), db.cache())
    val workManager: WorkManager by lazy { WorkManager.getInstance(context) }
    val notifier = dev.mealprep.app.notify.Notifier(context)
    val imports: dev.mealprep.app.work.ImportQueue by lazy { dev.mealprep.app.work.ImportQueue(workManager) }
    val jobs: dev.mealprep.app.work.JobWatcher by lazy { dev.mealprep.app.work.JobWatcher(workManager) }
    /** A share that arrived (MainActivity) and is waiting for the Share screen. */
    val pendingShare = MutableStateFlow<ShareInput?>(null)

    fun workerFactory(): WorkerFactory = MealPrepWorkerFactory(this)
}
