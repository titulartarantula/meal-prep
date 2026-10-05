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

    private fun vm(today: String) = ShareViewModel(env.repo, ImportQueue(wm), configured = { true }, today = { LocalDate.parse(today) })
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
}
