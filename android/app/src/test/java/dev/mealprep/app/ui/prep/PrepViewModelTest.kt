package dev.mealprep.app.ui.prep

import androidx.lifecycle.viewModelScope
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.data.api.Progress
import dev.mealprep.app.fixture
import dev.mealprep.app.work.JobWatcher
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrepViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    private val wk = LocalDate.parse("2026-10-11")
    private lateinit var wm: WorkManager
    private val vms = mutableListOf<PrepViewModel>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(env.context)
    }

    // Polling and card saving must stop before the cache closes (a late read fails the next test).
    @After fun tearDown() {
        runBlocking { withTimeout(5_000) { vms.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } } }
        env.close()
    }

    private fun vm() = PrepViewModel(env.repo, JobWatcher(wm), wk, pollMs = 10).also { vms += it }
    private val ready = fixture("prep_plan_ready.json")
    private val card21 = fixture("cook_card.json")
    private val card22 = card21.replace("\"entry_id\": 21", "\"entry_id\": 22")
    private fun plan(json: String) = Http.json.decodeFromString(PrepPlan.serializer(), json)

    private suspend fun awaitCount(method: String, path: String, n: Int) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { while (env.count(method, path) < n) delay(10) }
    }

    @Test fun `withTask recounts the Sunday checklist (day-of tasks don't count)`() {
        val p = plan(ready)
        val pack = p.sections[3].tasks[0]
        val q = p.withTask(pack.copy(done = true))
        assertEquals(2, q.checklist.done); assertEquals(4, q.checklist.total)
        assertTrue(q.sections[3].tasks[0].done)
    }

    @Test fun `building text says plan first, then cards`() {
        val b = plan(fixture("prep_plan_building.json"))
        assertEquals("Writing the prep plan…", buildingText(b))
        assertEquals("Writing cook cards: 1 of 2", buildingText(b.copy(progress = Progress(2, 3))))
    }

    @Test fun `building plan polls until ready`() = runTest {
        env.onSequence("GET", "/weeks/2026-10-11/prep-plan", listOf(fixture("prep_plan_building.json"), ready))
        val s = vm().state.await { it.plan?.status == "ready" }
        assertEquals(4, s.shown!!.checklist.total)
    }

    @Test fun `ticking a task sends done and updates the count`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = ready)
        env.on("PATCH", "/prep-plans/4/tasks/pack-1", body = """{"id":"pack-1","section":"pack","text":"TUE – chili kit","done":true,"done_at":"2026-10-11T15:10:00+00:00"}""")
        val vm = vm()
        val task = vm.state.await { it.plan != null }.plan!!.sections[3].tasks[0]
        vm.toggle(task)
        assertEquals(2, vm.state.value.shown!!.checklist.done)                           // optimistic
        assertEquals("""{"done":true}""", env.awaitBody("PATCH", "/prep-plans/4/tasks/pack-1"))
        assertEquals("2026-10-11T15:10:00+00:00", vm.state.await { it.shown!!.sections[3].tasks[0].doneAt != null }.shown!!.sections[3].tasks[0].doneAt)
    }

    @Test fun `two quick ticks both stay ticked`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = ready)
        env.onDelayed("PATCH", "/prep-plans/4/tasks/pack-1", 200, """{"id":"pack-1","section":"pack","text":"TUE – chili kit","done":true}""")
        env.on("PATCH", "/prep-plans/4/tasks/pack-2", body = """{"id":"pack-2","section":"pack","text":"THU – fish soup kit","done":true}""")
        val vm = vm()
        val pack = vm.state.await { it.plan != null }.plan!!.sections[3].tasks
        vm.toggle(pack[0]); vm.toggle(pack[1])
        awaitCount("PATCH", "/prep-plans/4/tasks/pack-1", 1); awaitCount("PATCH", "/prep-plans/4/tasks/pack-2", 1)
        withContext(Dispatchers.Default) { delay(400) }                                  // both answers are in
        assertEquals(3, vm.state.value.shown!!.checklist.done)
    }

    @Test fun `tick off the home network reverts with a plain message`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = ready)
        val vm = vm()
        val task = vm.state.await { it.plan != null }.plan!!.sections[3].tasks[0]
        env.offline = true
        vm.toggle(task)
        val s = vm.state.await { it.error != null }
        assertTrue(s.error!!.contains("home Wi-Fi"))
        assertFalse(s.shown!!.sections[3].tasks[0].done)
        assertEquals(1, s.shown!!.checklist.done)
    }

    @Test fun `day-of tasks can't be ticked`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = ready)
        val vm = vm()
        val cilantro = vm.state.await { it.plan != null }.plan!!.sections[0].tasks[1]
        vm.toggle(cilantro)
        assertFalse(vm.state.value.shown!!.sections[0].tasks[1].done)
        assertEquals(0, env.count("PATCH", "/prep-plans/4/tasks/knife-2"))
    }

    @Test fun `offline the saved plan is shown and says so`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = ready)
        vm().state.await { it.plan != null }
        env.offline = true
        val s = vm().state.await { !it.loading }
        assertEquals(4, s.shown!!.id)
        assertNotNull(s.offlineSince)
        assertNull(s.error)
    }

    @Test fun `start asks the server for this week and watches the job`() = runTest {
        env.on("POST", "/prep-plans", code = 202, body = """{"id":5,"status":"building"}""")
        val vm = vm()
        vm.state.await { !it.loading }                                 // no plan yet (404)
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = fixture("prep_plan_building.json"))
        vm.start()
        assertEquals("""{"weeks":["2026-10-11"]}""", env.awaitBody("POST", "/prep-plans"))
        vm.state.await { it.plan?.status == "building" }
        assertEquals(1, wm.getWorkInfosForUniqueWork("watch-prep-5").get().size)
    }

    @Test fun `nothing placed shows the server's reason`() = runTest {
        env.on("POST", "/prep-plans", code = 422, body = """{"detail":"no recipes planned for the week of 2026-10-11"}""")
        val vm = vm(); vm.state.await { !it.loading }
        vm.start()
        assertEquals("no recipes planned for the week of 2026-10-11", vm.state.await { it.error != null }.error)
    }

    @Test fun `failed regeneration falls back to the last good plan`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = """{"id":6,"status":"failed","error":"AI timed out","weeks":["2026-10-11"],"last_ready_id":4}""")
        env.on("GET", "/prep-plans/4", body = ready)
        val s = vm().state.await { it.previous != null }
        assertEquals(4, s.shown!!.id)
        assertEquals("AI timed out", s.plan!!.error)
    }

    @Test fun `while a new plan is written the last one stays readable`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = fixture("prep_plan_building.json"))   // id 5, last ready 4
        env.on("GET", "/prep-plans/4", body = ready)
        val s = vm().state.await { it.previous != null }
        assertEquals("building", s.plan!!.status)
        assertEquals(4, s.shown!!.id)
        assertTrue(s.hasTicks)
    }

    @Test fun `a ready plan saves its cook cards for the kitchen offline`() = runTest {
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = ready)
        env.on("GET", "/plan/21/card", body = card21)
        env.on("GET", "/plan/22/card", body = card22)
        vm().state.await { it.plan != null }
        awaitCount("GET", "/plan/22/card", 1)
        // Saved from this plan already: not asked again.
        vm().state.await { it.plan != null }
        withContext(Dispatchers.Default) { delay(200) }
        assertEquals(1, env.count("GET", "/plan/21/card"))
        env.offline = true
        assertEquals("Chili", env.repo.card(21).value!!.title)
        assertEquals(22, env.repo.card(22).value!!.entryId)
    }
}
