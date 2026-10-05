package dev.mealprep.app.ui.home

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.fixture
import dev.mealprep.app.work.ImportQueue
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.mealprep.app.work.ImportWorker
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    private val wk = LocalDate.parse("2026-10-11")
    private lateinit var vm: HomeViewModel

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        env.on("GET", "/weeks/2026-10-11", body = fixture("week_home.json"))
        env.on("GET", "/weeks", body = """[{"week":"2026-10-11","entries":3,"carted":false}]""")
        vm = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-07") })
    }
    @After fun tearDown() = env.close()

    @Test fun `loads nights, tray, status and the next step`() = runTest {
        val ui = vm.week(wk).await { !it.loading }
        assertEquals(listOf(23), ui.view!!.unplaced.map { it.id })
        assertEquals(listOf("Chili"), ui.view!!.nights[2].entries.map { it.title })
        assertEquals(StatusStrip(planned = true, cartSent = false, prepDone = false), ui.strip)
        assertEquals(ContextAction.BuildCart(wk), ui.action)
    }

    @Test fun `placing an entry patches its day`() = runTest {
        env.on("PATCH", "/plan/23", code = 204)
        val cookies = vm.week(wk).await { !it.loading }.view!!.unplaced.single()
        vm.place(cookies, 4)
        assertEquals(listOf(22, 23), vm.week(wk).value.view!!.nights[4].entries.map { it.id })   // optimistic
        assertEquals("""{"day":4}""", env.awaitBody("PATCH", "/plan/23"))
    }

    @Test fun `failed change reverts and says why`() = runTest {
        env.on("PATCH", "/plan/23", code = 500, body = """{"detail":"boom"}""")
        val cookies = vm.week(wk).await { !it.loading }.view!!.unplaced.single()
        vm.place(cookies, 4)
        assertEquals("boom", vm.message.await { it != null })
        assertEquals(listOf(23), vm.week(wk).await { it.view!!.unplaced.isNotEmpty() }.view!!.unplaced.map { it.id })
    }

    @Test fun `offline keeps the saved week and says so`() = runTest {
        vm.week(wk).await { !it.loading }
        env.offline = true
        vm.refresh(wk)
        val ui = vm.week(wk).await { it.offlineSince != null }
        assertNotNull(ui.view)
        assertEquals(3, ui.view!!.all.size)
    }

    @Test fun `a slow earlier load does not overwrite a later one`() = runTest {
        vm.week(wk).await { !it.loading }
        val old = fixture("week_home.json")
        val gate = CountDownLatch(1)
        env.onGated("GET", "/weeks/2026-10-11", gate, old)                    // stale: cookies still in the tray
        val before = env.count("GET", "/weeks/2026-10-11")
        vm.refresh(wk)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (env.count("GET", "/weeks/2026-10-11") == before) delay(10) } }
        env.on("GET", "/weeks/2026-10-11", body = old.replace("\"day\": null", "\"day\": 4"))
        vm.refresh(wk)
        vm.week(wk).await { it.view!!.unplaced.isEmpty() }
        gate.countDown()                                                      // the stale response is released only now
        withContext(Dispatchers.Default) { delay(500) }                       // time for it to (wrongly) land
        assertEquals(emptyList<Int>(), vm.week(wk).value.view!!.unplaced.map { it.id })
    }

    @Test fun `overlapping writes where the first fails end on the server's truth`() = runTest {
        val old = fixture("week_home.json")
        val ui = vm.week(wk).await { !it.loading }.view!!
        env.onDelayed("PATCH", "/plan/23", 400, """{"detail":"boom"}""", code = 500)   // A: cookies -> Thu, fails late
        env.on("PATCH", "/plan/21", code = 204)                                          // B: chili -> Fri, succeeds
        // server truth once B has landed: chili on Fri, cookies still in the tray, fish soup untouched
        env.on("GET", "/weeks/2026-10-11", body = old.replace("\"recipe_id\": 5, \"day\": 2", "\"recipe_id\": 5, \"day\": 5"))
        vm.place(ui.unplaced.single(), 4)
        vm.place(ui.all.first { it.id == 21 }, 5)
        assertEquals("boom", vm.message.await { it != null })
        val done = vm.week(wk).await { v -> v.view!!.nights[5].entries.map { it.id } == listOf(21) && v.view.unplaced.map { it.id } == listOf(23) }
        assertEquals(listOf(22), done.view!!.nights[4].entries.map { it.id })
    }

    @Test fun `a rejected token is an error, not offline`() = runTest {
        vm.week(wk).await { !it.loading }
        env.on("GET", "/weeks/2026-10-11", code = 401, body = """{"detail":"nope"}""")
        vm.refresh(wk)
        val ui = vm.week(wk).await { it.error != null }
        assertEquals(null, ui.offlineSince)
        assertEquals(3, ui.view!!.all.size)
    }

    @Test fun `a failed import that may be on the server is not re-sent by Try again`() = runTest {
        val id = UUID.randomUUID()
        val out = workDataOf(ImportWorker.KIND to ImportWorker.LINK, ImportWorker.TEXT to "x", ImportWorker.WEEK to "2026-10-11",
            ImportWorker.ERROR to "timed out", ImportWorker.RETRY_SAFE to false)
        val safeId = UUID.randomUUID()
        val flow = MutableStateFlow(listOf(ImportJob(id, WorkInfo.State.FAILED, out), ImportJob(safeId, WorkInfo.State.FAILED,
            workDataOf(ImportWorker.KIND to ImportWorker.LINK, ImportWorker.TEXT to "y", ImportWorker.WEEK to "2026-10-11",
                ImportWorker.ERROR to "no connection", ImportWorker.RETRY_SAFE to true))))
        val wm = WorkManager.getInstance(env.context)
        val v = HomeViewModel(env.repo, ImportQueue(wm), today = { LocalDate.parse("2026-10-07") }, jobs = flow)
        assertEquals(listOf(false, true), v.imports.await { it.size == 2 }.map { (it as ImportUi.Failed).retrySafe })
        v.retryImport(id)
        assertEquals(0, wm.getWorkInfosByTag(ImportQueue.TAG).get().size)
        v.retryImport(safeId)
        assertEquals(1, wm.getWorkInfosByTag(ImportQueue.TAG).get().size)
    }

    @Test fun `Cancel on an import waiting for the home network cancels its work`() = runTest {
        val wm = WorkManager.getInstance(env.context)
        val queue = ImportQueue(wm)
        val id = queue.enqueueLink("https://cooking.nytimes.com/recipes/1-x", wk)   // test WorkManager: network never met
        val v = HomeViewModel(env.repo, queue, today = { LocalDate.parse("2026-10-07") })
        v.cancelImport(id)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(id).get()!!.state)
    }
}
