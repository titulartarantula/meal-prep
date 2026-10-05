package dev.mealprep.app.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.mealprep.app.TestEnv
import dev.mealprep.app.fixture
import dev.mealprep.app.notify.Notifier
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class JobWatchWorkerTest {
    private val env = TestEnv()

    @Before fun setUp() {
        shadowOf(env.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier(env.context).ensureChannels()
    }
    @After fun tearDown() = env.close()

    private fun worker(kind: String, id: Int, foreground: Boolean = false, maxPolls: Int = 20, attempt: Int = 0) =
        TestListenableWorkerBuilder<JobWatchWorker>(env.context)
            .setInputData(workDataOf(JobWatchWorker.KIND to kind, JobWatchWorker.ID to id, JobWatchWorker.WEEK to "2026-10-11",
                JobWatchWorker.POLL_MS to 10L, JobWatchWorker.MAX_POLLS to maxPolls))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(c: Context, n: String, p: WorkerParameters) = JobWatchWorker(c, p, env.repo, Notifier(c)) { foreground }
            }).build()

    private fun notes() = shadowOf(env.context.getSystemService(NotificationManager::class.java)).allNotifications
    private fun title(n: Notification) = n.extras.getString(Notification.EXTRA_TITLE)
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test fun `notifies once when the app is in the background`() = runTest {
        env.onSequence("GET", "/drafts/7", listOf(fixture("draft_building.json"), fixture("draft_ready.json")))
        val r = worker(JobWatchWorker.DRAFT, 7).doWork() as Result.Success
        assertEquals("ready", r.outputData.getString(JobWatchWorker.OUT_STATUS))
        assertEquals("Cart ready to review", title(notes().single()))
        assertEquals("Week of Oct 11: check the picks, then Send to Loblaws.", text(notes().single()))
        worker(JobWatchWorker.DRAFT, 7).doWork()
        assertEquals(1, notes().size)                                 // de-duplicated
    }

    @Test fun `stays quiet while the app is open`() = runTest {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json"))
        assertTrue(worker(JobWatchWorker.DRAFT, 7, foreground = true).doWork() is Result.Success)
        assertTrue(notes().isEmpty())
    }

    @Test fun `failed draft is reported with the server's reason`() = runTest {
        env.on("GET", "/drafts/7", body = """{"id":7,"status":"failed","error":"Every product search failed. Try again later."}""")
        worker(JobWatchWorker.DRAFT, 7).doWork()
        val n = notes().single()
        assertEquals("Cart couldn't be built", title(n))
        assertEquals("Every product search failed. Try again later.", text(n))
    }

    @Test fun `failed prep plan is reported with the server's reason`() = runTest {
        env.on("GET", "/prep-plans/5", body = """{"id":5,"status":"failed","error":"AI timed out after 900 s"}""")
        worker(JobWatchWorker.PREP, 5).doWork()
        val n = notes().single()
        assertEquals("Prep plan failed", title(n))
        assertEquals("AI timed out after 900 s", text(n))
    }

    @Test fun `still building after the polls retries, then gives up with a note`() = runTest {
        env.on("GET", "/prep-plans/5", body = fixture("prep_plan_building.json"))
        assertTrue(worker(JobWatchWorker.PREP, 5, maxPolls = 3).doWork() is Result.Retry)
        assertTrue(notes().isEmpty())
        assertTrue(worker(JobWatchWorker.PREP, 5, maxPolls = 3, attempt = JobWatchWorker.MAX_ATTEMPTS - 1).doWork() is Result.Failure)
        assertEquals("Prep plan failed", title(notes().single()))
        assertEquals(JobWatchWorker.GAVE_UP, text(notes().single()))
    }

    @Test fun `off the home network it keeps polling, then retries quietly`() = runTest {
        env.offline = true
        assertTrue(worker(JobWatchWorker.DRAFT, 7, maxPolls = 3).doWork() is Result.Retry)
        assertTrue(notes().isEmpty())
    }

    @Test fun `a draft the server doesn't know stops without a notification`() = runTest {
        assertTrue(worker(JobWatchWorker.DRAFT, 99).doWork() is Result.Failure)   // TestEnv: unknown route → 404
        assertTrue(notes().isEmpty())
    }
}
