package dev.mealprep.app.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.TestEnv
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.fixture
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReminderSchedulerTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val env = TestEnv()
    private lateinit var wm: WorkManager
    private val zone = ZoneId.of("America/Toronto")
    private val now = ZonedDateTime.of(LocalDateTime.parse("2026-10-11T10:00"), zone)
    @Volatile private var wanted = true
    private fun r(id: String, at: String) = Reminder(id, ReminderKind.RATE, LocalDateTime.parse(at), "Title $id", "Text $id", "card/1")

    @Before fun setUp() {
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier(ctx).ensureChannels()
        val factory = object : WorkerFactory() {
            override fun createWorker(c: Context, n: String, p: WorkerParameters) = ReminderWorker(c, p, Notifier(c)) { wanted }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        wm = WorkManager.getInstance(ctx)
    }
    @After fun tearDown() = env.close()

    private fun info(id: String) = wm.getWorkInfosForUniqueWork(ReminderScheduler.name(id)).get().single()
    private fun notes() = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    private suspend fun runDue(id: String) {
        val work = info(id).id
        WorkManagerTestInitHelper.getTestDriver(ctx)!!.setInitialDelayMet(work)
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (!wm.getWorkInfoById(work).get()!!.state.isFinished) delay(20) } }
    }

    @Test fun `reconcile schedules wanted reminders and cancels the rest`() {
        val s = ReminderScheduler(wm)
        s.reconcile(listOf(r("rate-1", "2026-10-14T09:00"), r("rate-2", "2026-10-15T09:00")), now)
        assertEquals(WorkInfo.State.ENQUEUED, info("rate-1").state)
        s.reconcile(listOf(r("rate-2", "2026-10-15T09:00")), now)
        assertEquals(WorkInfo.State.CANCELLED, info("rate-1").state)
        assertEquals(WorkInfo.State.ENQUEUED, info("rate-2").state)
        assertEquals(Duration.ofHours(95).toMillis(), info("rate-2").initialDelayMillis)   // Sun 10:00 → Thu 09:00
    }

    @Test fun `a reminder already due but not run yet is left to run`() {
        val s = ReminderScheduler(wm)
        s.reconcile(listOf(r("rate-1", "2026-10-11T10:30")), now)
        s.reconcile(emptyList(), now.plusMinutes(45))                       // due 15 min ago, Doze held it back
        assertEquals(WorkInfo.State.ENQUEUED, info("rate-1").state)
    }

    @Test fun `a due reminder posts its notification`() = runTest {
        ReminderScheduler(wm).reconcile(listOf(r("rate-1", "2026-10-14T09:00")), now)
        runDue("rate-1")
        val n = notes().single()
        assertEquals("Title rate-1", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Text rate-1", n.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun `a reminder no longer wanted when it's due stays quiet`() = runTest {
        wanted = false
        ReminderScheduler(wm).reconcile(listOf(r("rate-1", "2026-10-14T09:00")), now)
        runDue("rate-1")
        assertTrue(notes().isEmpty())
    }

    @Test fun `when due, a dinner rated or moved meanwhile needs no reminder`() = runBlocking {
        val wk = LocalDate.parse("2026-10-11")
        env.on("GET", "/weeks/2026-10-11", body = fixture("week_home.json"))
        env.on("GET", "/weeks/2026-10-11/prep-plan", body = fixture("prep_plan_ready.json"))
        val rate21 = Reminder("rate-21", ReminderKind.RATE, LocalDateTime.parse("2026-10-14T09:00"), "", "", "", 21, wk)
        assertTrue(Reminders.stillWanted(rate21, env.repo, NotifPrefs()))
        assertFalse(Reminders.stillWanted(rate21.copy(id = "rate-22", entryId = 22), env.repo, NotifPrefs()))   // rated already
        assertFalse(Reminders.stillWanted(rate21.copy(at = LocalDateTime.parse("2026-10-15T09:00")), env.repo, NotifPrefs()))  // moved
        assertFalse(Reminders.stillWanted(rate21, env.repo, NotifPrefs(rate = false)))                       // switched off
        val thaw = Reminder("thaw-22", ReminderKind.THAW, LocalDateTime.parse("2026-10-14T20:00"), "", "", "", 22, wk)
        assertTrue(Reminders.stillWanted(thaw, env.repo, NotifPrefs()))
        env.offline = true                                                                                   // the saved copies decide
        assertFalse(Reminders.stillWanted(rate21.copy(id = "rate-22", entryId = 22), env.repo, NotifPrefs()))
        assertTrue(Reminders.stillWanted(rate21.copy(week = LocalDate.parse("2026-11-01")), env.repo, NotifPrefs()))  // nothing known: show it
    }
}
