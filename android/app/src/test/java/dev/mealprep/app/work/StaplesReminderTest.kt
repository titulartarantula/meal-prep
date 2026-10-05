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
import androidx.test.core.app.ApplicationProvider
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.notify.Notifier
import dev.mealprep.app.ui.nav.ListRoute
import dev.mealprep.app.ui.nav.Nav
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StaplesReminderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/Toronto")
    private lateinit var wm: WorkManager
    private val on = NotifPrefs(staples = true)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(ctx)
        shadowOf(ctx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier(ctx).ensureChannels()
    }

    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), zone)

    @Test fun `next reminder is the coming Saturday at 9`() {
        val sat9 = at("2026-10-10T09:00")
        assertEquals(sat9, StaplesReminder.next(at("2026-10-07T15:00"), DayOfWeek.SATURDAY, LocalTime.of(9, 0)))
        assertEquals(sat9, StaplesReminder.next(at("2026-10-10T08:59"), DayOfWeek.SATURDAY, LocalTime.of(9, 0)))
        assertEquals(sat9.plusWeeks(1), StaplesReminder.next(sat9, DayOfWeek.SATURDAY, LocalTime.of(9, 0)))  // just shown
        // Clocks go back on Sunday Nov 1: still 9:00 on the wall the Saturday after.
        assertEquals(at("2026-11-07T09:00"), StaplesReminder.next(at("2026-10-31T10:00"), DayOfWeek.SATURDAY, LocalTime.of(9, 0)))
    }

    @Test fun `default is off, Saturday 9 00`() {
        val d = NotifPrefs()
        assertEquals(false, d.staples); assertEquals(DayOfWeek.SATURDAY, d.staplesDay); assertEquals(LocalTime.of(9, 0), d.staplesAt)
    }

    @Test fun `turning it on queues one reminder, off cancels it`() {
        StaplesReminder.apply(wm, on, at("2026-10-07T15:00"))
        StaplesReminder.apply(wm, on, at("2026-10-07T15:00"))
        val infos = wm.getWorkInfosForUniqueWork(StaplesReminder.WORK).get()
        assertEquals(1, infos.count { it.state == WorkInfo.State.ENQUEUED })
        val delay = infos.single { it.state == WorkInfo.State.ENQUEUED }.nextScheduleTimeMillis - System.currentTimeMillis()
        assertEquals(java.time.Duration.ofHours(66).toMillis().toDouble(), delay.toDouble(), 5_000.0)   // Wed 15:00 → Sat 9:00
        StaplesReminder.apply(wm, on.copy(staples = false))
        assertTrue(wm.getWorkInfosForUniqueWork(StaplesReminder.WORK).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    private fun worker(prefs: NotifPrefs) = TestListenableWorkerBuilder<StaplesReminderWorker>(ctx)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, n: String, p: WorkerParameters) =
                StaplesReminderWorker(c, p, Notifier(c), wm, now = { at("2026-10-10T09:00") }, prefs = { prefs })
        }).build()

    private fun notes() = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun `the reminder opens the shopping list and queues next week's`() = runTest {
        assertEquals(Result.success(), worker(on).doWork())
        val n = notes().single()
        assertEquals("Time to check the staples", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(ListRoute(""), Nav.parse(Nav.list()))
        assertEquals(1, wm.getWorkInfosForUniqueWork(StaplesReminder.WORK).get().count { !it.state.isFinished })
    }

    @Test fun `turned off meanwhile posts nothing`() = runTest {
        assertEquals(Result.success(), worker(NotifPrefs()).doWork())
        assertTrue(notes().isEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork(StaplesReminder.WORK).get().isEmpty())
    }
}
