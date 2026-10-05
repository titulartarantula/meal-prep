package dev.mealprep.app.ui.share

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.fixture
import dev.mealprep.app.notify.Notifier
import dev.mealprep.app.work.ImportQueue
import dev.mealprep.app.work.ImportWorker
import dev.mealprep.app.ui.camera.PageStore
import android.net.Uri
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val env = TestEnv()
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, n: String, p: WorkerParameters) = ImportWorker(c, p, env.repo, Notifier(c))
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        wm = WorkManager.getInstance(env.context)
    }
    @After fun tearDown() = env.close()

    private fun vm(today: String) = ShareViewModel(
        env.repo, ImportQueue(wm), PageStore(tmp.root),
        copy = { uri, out -> if (uri.toString().contains("bad")) error("gone") else out.writeText("img-${uri.lastPathSegment}") },
        configured = { true }, today = { LocalDate.parse(today) })
    private suspend fun awaitWork(id: java.util.UUID): WorkInfo = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (!wm.getWorkInfoById(id).get()!!.state.isFinished) delay(20) }
        wm.getWorkInfoById(id).get()!!
    }
    private val nyt = ShareInput.NytLink("https://cooking.nytimes.com/recipes/1015819-x", "Cookies https://cooking.nytimes.com/recipes/1015819-x")

    @Test fun `NYT link defaults to the coming Sunday and the import posts the url to that week`() = runTest {
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        env.on("POST", "/recipes/share", body = fixture("share_result.json"))
        val vm = vm("2026-10-07")
        vm.start(nyt)
        val s = vm.state.await { st -> st.options.any { it.detail != null } }
        assertEquals(LocalDate.parse("2026-10-11"), s.selected)
        assertEquals(LocalDate.parse("2026-10-04"), s.options.first().week)
        assertEquals(8, s.options.size)
        assertTrue(s.canConfirm)
        val id = vm.confirm()!!
        assertTrue(vm.state.value.queued)
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(id)
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (wm.getWorkInfoById(id).get()!!.state != WorkInfo.State.SUCCEEDED) delay(20) }
        }
        assertEquals("""{"text":"https://cooking.nytimes.com/recipes/1015819-x","week":"2026-10-11"}""",
            env.bodies("POST", "/recipes/share").single())
    }

    @Test fun `on a Sunday the default is this week`() = runTest {
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        val vm = vm("2026-10-11"); vm.start(nyt)
        assertEquals(LocalDate.parse("2026-10-11"), vm.state.value.selected)
        vm.state.await { it.weeksChecked }      // own the /weeks request so it doesn't leak past the test
    }

    @Test fun `not a recipe shows the message and queues nothing`() {
        val vm = vm("2026-10-07")
        vm.start(ShareInput.NotARecipe("https://www.nytimes.com/2026/10/01/dining/fall-soups.html"))
        assertEquals(ShareViewModel.NOT_A_RECIPE, vm.state.value.message)
        assertFalse(vm.state.value.canConfirm)
        assertNull(vm.confirm())
        assertTrue(wm.getWorkInfosByTag(ImportQueue.TAG).get().isEmpty())
        assertTrue(env.requests.isEmpty())
    }

    @Test fun `offline still offers eight local weeks`() = runTest {
        env.offline = true
        val vm = vm("2026-10-07"); vm.start(nyt)
        val s = vm.state.await { it.weeksChecked }   // the offline fetch has failed by now
        assertEquals(8, s.options.size)
        assertTrue(s.options.all { it.detail == null })
        assertEquals(LocalDate.parse("2026-10-11"), s.selected)
    }

    @Test fun `shared photos are copied in at once, can be reordered, and import in that order`() = runTest {
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        env.on("POST", "/recipes/photo", body = fixture("share_result.json"))
        val vm = vm("2026-10-07")
        vm.start(ShareInput.Photos(listOf(Uri.parse("content://m/1"), Uri.parse("content://m/2"))))
        vm.state.await { it.pages.pages.size == 2 && !it.copying && it.weeksChecked }
        vm.movePage(1, -1)
        vm.setTitle("  Lemon Bars ")
        val id = vm.confirm()!!
        WorkManagerTestInitHelper.getTestDriver(env.context)!!.setAllConstraintsMet(id)
        assertEquals(WorkInfo.State.SUCCEEDED, awaitWork(id).state)
        val body = env.bodies("POST", "/recipes/photo").single()
        assertTrue(body.indexOf("img-2") in 0 until body.indexOf("img-1"))
        assertTrue(body.contains("filename=\"page01.jpg\""))
        assertTrue(body.contains("2026-10-11"))
        assertTrue(body.contains("\r\n\r\nLemon Bars\r\n"))       // trimmed title hint
        assertTrue(tmp.root.listFiles()!!.isEmpty())               // pages deleted once the server has the recipe
    }

    @Test fun `more than ten pages must be trimmed first`() = runTest {
        val vm = vm("2026-10-07")
        vm.start(ShareInput.Photos((1..11).map { Uri.parse("content://m/$it") }))
        val s = vm.state.await { it.pages.pages.size == 11 && !it.copying }
        assertFalse(s.canConfirm)
        assertEquals(ShareViewModel.TOO_MANY, s.message)
        assertNull(vm.confirm())
        vm.removePage(10)
        assertTrue(vm.state.value.canConfirm)
        assertNull(vm.state.value.message)
        vm.state.await { it.weeksChecked }
    }

    @Test fun `unreadable photos are left out and said so, and none readable means nothing to send`() = runTest {
        val vm = vm("2026-10-07")
        vm.start(ShareInput.Photos(listOf(Uri.parse("content://m/1"), Uri.parse("content://m/bad"))))
        val s = vm.state.await { !it.copying && it.dir != null && it.weeksChecked }
        assertEquals(1, s.pages.pages.size)
        assertEquals(ShareViewModel.someUnreadable(1, 2), s.message)
        assertTrue(s.canConfirm)
        vm.start(ShareInput.Photos(listOf(Uri.parse("content://m/bad"))))
        val none = vm.state.await { !it.copying && it.dir != null && it.weeksChecked }
        assertEquals(ShareViewModel.UNREADABLE, none.message)
        assertFalse(none.canConfirm)
        assertEquals(1, tmp.root.listFiles()!!.size)                 // the first share's pages were discarded
    }

    @Test fun `camera pages arrive numbered and a cancelled share deletes them`() = runTest {
        val store = PageStore(tmp.root)
        val dir = store.newBatch()
        store.commitOrder(dir, listOf(store.newFile(dir).apply { writeText("a") }, store.newFile(dir).apply { writeText("b") }))
        val vm = vm("2026-10-07")
        vm.start(ShareInput.Pages(dir))
        val s = vm.state.await { it.weeksChecked }
        assertEquals(listOf("a", "b"), s.pages.pages.map { it.readText() })
        assertTrue(s.canConfirm)
        vm.start(ShareInput.NotARecipe("hello"))                     // replaced by another share before confirming
        assertFalse(dir.exists())
    }
}
