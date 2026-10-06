package dev.mealprep.app.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.TestEnv
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.fixture
import dev.mealprep.app.notify.Notifier
import dev.mealprep.app.notify.ReminderScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SyncWorkerTest {
    private val env = TestEnv()
    private lateinit var wm: WorkManager
    private val now = ZonedDateTime.of(LocalDateTime.parse("2026-10-11T10:00"), ZoneId.of("America/Toronto"))
    private var settings = Settings(token = "tok")
    private var foreground = false

    @Before fun setUp() {
        shadowOf(env.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier(env.context).ensureChannels()
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(env.context)
        env.on("GET", "/weeks/2026-10-11", body = """[{"id":21,"week":"2026-10-11","recipe_id":5,"day":2,"title":"Chili"},
            {"id":22,"week":"2026-10-11","recipe_id":6,"day":4,"title":"Fish soup"}]""")
        env.on("GET", "/weeks/2026-10-04", body = "[]")
        env.on("GET", "/weeks/2026-10-18", body = "[]")
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = fixture("prep_plan_ready.json"))
        env.on("GET", "/plan/21/card", body = fixture("cook_card.json"))
        env.on("GET", "/recipes", body = "[]")
        env.on("GET", "/weeks/2026-10-11/draft", body = fixture("draft_ready.json"))
    }
    @After fun tearDown() = env.close()

    private fun worker() = TestListenableWorkerBuilder<SyncWorker>(env.context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, n: String, p: WorkerParameters) =
                SyncWorker(c, p, env.repo, { settings }, ReminderScheduler(wm), Notifier(c), { foreground }) { now }
        }).build()

    private fun notes() = shadowOf(env.context.getSystemService(NotificationManager::class.java)).allNotifications
    private fun queued() = wm.getWorkInfosByTag(ReminderScheduler.TAG).get().filter { it.state == WorkInfo.State.ENQUEUED }
        .flatMap { it.tags }.filter { it.startsWith("reminder-") }.toSet()

    @Test fun `sync schedules this week's reminders, saves cards offline and announces the cart once`() = runTest {
        assertTrue(worker().doWork() is Result.Success)
        assertEquals(setOf("reminder-rate-21", "reminder-rate-22", "reminder-thaw-22"), queued())
        assertEquals("Cart ready to review", notes().single().extras.getString(Notification.EXTRA_TITLE))
        worker().doWork()
        assertEquals(1, notes().size)
        env.offline = true
        assertNotNull(env.repo.card(21).value)                                  // readable away from home
        val planned = env.repo.getLocal(SyncWorker.PLANNED, SyncWorker.plannedSerializer)!!
        assertEquals(listOf("rate-21", "thaw-22", "rate-22"), planned.map { it.id })   // Wed 9:00, Wed 20:00, Fri 9:00
    }

    @Test fun `away from home the saved copies keep the reminders, a week never saved changes nothing`() = runTest {
        worker().doWork()
        env.offline = true
        worker().doWork()
        assertEquals(setOf("reminder-rate-21", "reminder-rate-22", "reminder-thaw-22"), queued())
        env.offline = false
        env.db.clearAllTables()
        env.offline = true
        worker().doWork()                                                         // nothing known now: keep what's queued
        assertEquals(setOf("reminder-rate-21", "reminder-rate-22", "reminder-thaw-22"), queued())
    }

    @Test fun `switching a kind off cancels its reminders, no cart notice while the app is open`() = runTest {
        foreground = true
        worker().doWork()
        assertTrue(notes().isEmpty())
        settings = settings.copy(notif = NotifPrefs(rate = false, cartReady = false))
        foreground = false
        worker().doWork()
        assertEquals(setOf("reminder-thaw-22"), queued())
        assertTrue(notes().isEmpty())
    }

    @Test fun `not set up yet does nothing`() = runTest {
        settings = Settings()
        assertTrue(worker().doWork() is Result.Success)
        assertTrue(env.requests.isEmpty())
    }
}
