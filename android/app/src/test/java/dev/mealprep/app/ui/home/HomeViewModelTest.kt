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

    @Test fun `the pager reaches 3 weeks ahead, further when a later week already has recipes`() = runTest {
        assertEquals(3, vm.ahead.value)
        fun later() = env.requests.firstOrNull { it.url.encodedPath == "/weeks" && it.url.queryParameter("count") == "48" }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (later() == null) delay(10) } }
        val asked = later()!!
        assertEquals("2026-11-01", asked.url.queryParameter("from"))                // the first week after the horizon
        env.on("GET", "/weeks", body = """[{"week":"2026-11-01","entries":0},{"week":"2026-11-15","entries":1}]""")
        vm.refreshAhead()
        vm.ahead.await { it == 6 }
        env.offline = true                                                         // the saved copy still shows it
        vm.refreshAhead()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(6, vm.ahead.value)
    }

    @Test fun `pager span keeps past weeks and opens a linked week`() {
        assertEquals(PagerSpan(pages = 8, initial = 4), pagerSpan(ahead = 3, opened = 0))      // 4 back, this, 3 ahead
        assertEquals(PagerSpan(8, 5), pagerSpan(3, 1))
        assertEquals(PagerSpan(11, 10), pagerSpan(3, 6))                                       // a link to a later week
        assertEquals(PagerSpan(10, 4), pagerSpan(5, 0))
        assertEquals(PagerSpan(8, 0), pagerSpan(3, -9))                                        // far past: the oldest page
    }

    @Test fun `loads nights, tray, status and the next step`() = runTest {
        val ui = vm.week(wk).await { !it.loading }
        assertEquals(listOf(23), ui.view!!.unplaced.map { it.id })
        assertEquals(listOf("Chili"), ui.view!!.nights[2].entries.map { it.title })
        assertEquals(StatusStrip(planned = true, cartSent = false, prepDone = false), ui.strip)
        assertEquals(ContextAction.BuildCart(wk), ui.action)
    }

    @Test fun `a week changed after its cart was sent is planned only and builds a new cart`() = runTest {
        env.on("GET", "/weeks", body = """[{"week":"2026-10-11","entries":3,"carted":false,"cart_stale":true}]""")
        env.on("GET", "/weeks/2026-10-11/draft", body = fixture("draft_3_sent.json").replaceFirst("{", "{\"stale\": true, "))
        val ui = vm.week(wk).await { !it.loading }
        assertEquals(StatusStrip(planned = true, cartSent = false, prepDone = false), ui.strip)
        assertEquals(ContextAction.BuildCart(wk), ui.action)
        assertEquals(null, ui.sentDraftId)                                         // "Cart sent" doesn't open the old one
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

    @Test fun `a removed dinner waits for Undo, Undo keeps it and nothing is deleted`() = runTest {
        env.on("DELETE", "/plan/21", code = 204)
        val chili = vm.week(wk).await { !it.loading }.view!!.all.first { it.id == 21 }
        vm.remove(chili)
        assertEquals(chili, vm.removed.value)
        assertEquals(false, vm.week(wk).value.view!!.all.any { it.id == 21 })        // gone from the screen at once
        vm.refresh(wk)                                                                // a reload doesn't bring it back
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(false, vm.week(wk).value.view!!.all.any { it.id == 21 })
        vm.undoRemove()
        assertEquals(null, vm.removed.value)
        vm.week(wk).await { v -> v.view!!.all.any { it.id == 21 } }
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(0, env.count("DELETE", "/plan/21"))
    }

    @Test fun `a removal is deleted once the snackbar goes, or when another dinner is removed`() = runTest {
        var changes = 0
        val v = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-07") },
            afterChange = { changes++ })
        env.on("DELETE", "/plan/21", code = 204)
        env.on("DELETE", "/plan/23", code = 204)
        val all = v.week(wk).await { !it.loading }.view!!.all
        v.remove(all.first { it.id == 21 })
        assertEquals(0, env.count("DELETE", "/plan/21"))
        v.remove(all.first { it.id == 23 })                                          // the first one is final now
        env.awaitBody("DELETE", "/plan/21")
        assertEquals(23, v.removed.value!!.id)
        v.commitRemove()
        env.awaitBody("DELETE", "/plan/23")
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (changes < 2) delay(10) } }
        assertEquals(null, v.removed.value)
    }

    @Test fun `a failed load with no saved copy is unknown, not an empty week`() = runTest {
        env.offline = true
        val ui = vm.week(LocalDate.parse("2026-11-01")).await { !it.loading }
        assertEquals(null, ui.view)
        assertNotNull(ui.error)
        assertEquals(true, ui.failed)
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
        val id = queue.enqueueLink("https://cooking.nytimes.com/recipes/1-x")   // test WorkManager: network never met
        val v = HomeViewModel(env.repo, queue, today = { LocalDate.parse("2026-10-07") })
        v.cancelImport(id)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfoById(id).get()!!.state)
    }

    @Test fun `the entry dialog learns about a missing page from the recipe`() = runTest {
        env.on("GET", "/recipes/3", body = fixture("share_result.json").let { it.substring(it.indexOf("{", 1), it.indexOf(",\n \"entry\"")) })
        env.on("GET", "/recipes/1", body = fixture("recipe_1.json"))
        assertEquals(dev.mealprep.app.ui.camera.RefPrompt(3, 1, "Batter for 24 crêpes, page 191", 191), vm.refPromptFor(3))
        assertEquals(null, vm.refPromptFor(1))
        env.offline = true
        assertEquals(null, vm.refPromptFor(99))                     // offline and never saved: nothing to offer
    }

    @Test fun `yesterday's unrated dinner is offered for rating, Later hides it on this phone until tomorrow`() = runTest {
        env.on("GET", "/ratings/pending", body = fixture("pending.json"))
        val v = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-14") })
        val p = v.pending.await { it != null }!!
        assertEquals(21, p.entryId); assertEquals("Chili", p.title)
        assertEquals("today=2026-10-14", env.requests.last { it.url.encodedPath == "/ratings/pending" }.url.query)
        v.dismissPending()
        assertEquals(null, v.pending.value)
        withContext(Dispatchers.Default) { delay(200) }                    // "Later" is written
        val asked = env.count("GET", "/ratings/pending")
        val again = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-14") })
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (env.count("GET", "/ratings/pending") == asked) delay(10) }; delay(200) }
        assertEquals(null, again.pending.value)
        val tomorrow = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-15") })
        assertEquals(21, tomorrow.pending.await { it != null }!!.entryId)
    }

    @Test fun `a change to the week re-plans the reminders, a failed one does not`() = runTest {
        var changes = 0
        val v = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-07") },
            afterChange = { changes++ })
        val cookies = v.week(wk).await { !it.loading }.view!!.unplaced.single()
        env.on("PATCH", "/plan/23", code = 500, body = """{"detail":"boom"}""")
        v.place(cookies, 4)
        v.message.await { it != null }
        assertEquals(0, changes)
        env.on("PATCH", "/plan/23", code = 204)
        v.place(cookies, 4)
        env.awaitBody("PATCH", "/plan/23")
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (changes == 0) delay(10) } }
        assertEquals(1, changes)
    }
}
