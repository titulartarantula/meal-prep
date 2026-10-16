package dev.mealprep.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.crossfade
import dev.mealprep.app.work.StaplesReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val PAGES_KEPT_MS = 14L * 24 * 3600 * 1000

class MealPrepApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
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

    /** Product photos (Coil): kept on disk whatever the image server's cache headers say (Coil 3's default), so a
     *  cart seen at home still shows its photos away from home or offline. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("image_cache")).maxSizePercent(0.02).build() }
        .crossfade(true)
        .build()

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(graph.workerFactory()).build()
}

val Context.graph: AppGraph get() = (applicationContext as MealPrepApp).graph
