package dev.mealprep.app

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.room.Room
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.SettingsApiProvider
import dev.mealprep.app.data.cache.CacheDb
import dev.mealprep.app.data.settings.SettingsStore
import dev.mealprep.app.ui.camera.ImageScaler
import dev.mealprep.app.ui.camera.PageStore
import dev.mealprep.app.work.MealPrepWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

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
    val reminders: dev.mealprep.app.notify.ReminderScheduler by lazy { dev.mealprep.app.notify.ReminderScheduler(workManager) }
    /** Cookbook pages waiting to be read, one folder per recipe (filesDir/pages; see FileProvider paths). */
    val pages = PageStore(File(context.filesDir, "pages"))
    /** Copies one shared or picked image into a page file: shrunk to 2000 px and upright. */
    val copyPage: suspend (Uri, File) -> Unit = { uri, out ->
        withContext(Dispatchers.IO) { ImageScaler.toJpeg(ImageDecoder.createSource(context.contentResolver, uri), out) }
    }
    /** Shrinks a camera capture into a page file. */
    val scalePage: suspend (File, File) -> Unit = { raw, out ->
        withContext(Dispatchers.IO) { ImageScaler.toJpeg(ImageDecoder.createSource(raw), out) }
    }
    /** Recipe files being imported (copied in from the picker or a share) and exports made for Save / Send…. */
    val importsDir get() = File(context.cacheDir, dev.mealprep.app.core.ImportFiles.DIR)
    val exportsDir get() = File(context.cacheDir, dev.mealprep.app.core.ExportFiles.DIR)
    /** Copies a picked or shared recipe file into [importsDir] while its read grant lasts. */
    suspend fun copyImport(uri: Uri): dev.mealprep.app.core.CopyResult =
        withContext(Dispatchers.IO) { dev.mealprep.app.core.ImportFiles.copyIn(context.contentResolver, uri, importsDir) }
    /** A share that arrived (MainActivity) and is waiting for the Share screen. */
    val pendingShare = MutableStateFlow<ShareInput?>(null)
    /** Import cards dismissed this run (This week and Recipes both show the cards). */
    val hiddenImports = MutableStateFlow<Set<java.util.UUID>>(emptySet())

    fun workerFactory(): WorkerFactory = MealPrepWorkerFactory(this)
}
