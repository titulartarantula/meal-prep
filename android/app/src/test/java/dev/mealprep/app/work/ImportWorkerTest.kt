package dev.mealprep.app.work

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import dev.mealprep.app.TestEnv
import dev.mealprep.app.fixture
import dev.mealprep.app.notify.Notifier
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ImportWorkerTest {
    private val env = TestEnv()
    @get:Rule val tmp = TemporaryFolder()
    private val link = ImportQueue.linkInput("https://cooking.nytimes.com/recipes/1-x", LocalDate.parse("2026-10-11"))

    @Before fun setUp() {
        shadowOf(env.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Notifier(env.context).ensureChannels()
    }
    @After fun tearDown() = env.close()

    private fun worker(input: Data, attempt: Int = 0, id: UUID = UUID.randomUUID()) = TestListenableWorkerBuilder<ImportWorker>(env.context)
        .setInputData(input).setRunAttemptCount(attempt).setId(id)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(c: Context, n: String, p: WorkerParameters) = ImportWorker(c, p, env.repo, Notifier(c))
        }).build()

    private fun notificationText() =
        notifications().single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun notifications() =
        shadowOf(env.context.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun `re-shared recipe reports the existing rating and the missing page`() = runTest {
        env.on("POST", "/recipes/share", body = fixture("share_result.json"))
        val r = worker(link).doWork()
        val out = (r as Result.Success).outputData
        assertEquals(3, out.getInt(ImportWorker.OUT_RECIPE_ID, 0))
        assertEquals(12, out.getInt(ImportWorker.OUT_ENTRY_ID, 0))
        assertTrue(out.getBoolean(ImportWorker.OUT_EXISTING, false))
        assertEquals(1, out.getInt(ImportWorker.OUT_MISSING_LINE, -1))
        assertEquals(191, out.getInt(ImportWorker.OUT_MISSING_PAGE, 0))
        assertEquals("2026-10-11", out.getString(ImportWorker.OUT_WEEK))
        assertEquals(listOf("""{"text":"https://cooking.nytimes.com/recipes/1-x","week":"2026-10-11"}"""),
            env.bodies("POST", "/recipes/share"))
        val n = notifications().single()
        assertEquals("Already in your library: Thai Green Curry Wings", n.extras.getString(Notification.EXTRA_TITLE))
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text.contains("Family 4.5/5")); assertTrue(text.contains("page 191"))
    }

    @Test fun `unreachable is retried until the last attempt, then fails with the input echoed`() = runTest {
        env.offline = true
        assertTrue(worker(link, attempt = 0).doWork() is Result.Retry)
        val last = worker(link, attempt = ImportWorker.MAX_ATTEMPTS - 1).doWork() as Result.Failure
        assertTrue(last.outputData.getString(ImportWorker.ERROR)!!.contains("home Wi-Fi"))
        assertTrue(last.outputData.getBoolean(ImportWorker.RETRY_SAFE, false))
        assertEquals(ImportWorker.LINK, last.outputData.getString(ImportWorker.KIND))
        assertEquals("Couldn't add the recipe", notifications().single().extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Can't reach the meal-prep server. Are you on home Wi-Fi (or WireGuard)? Open Meal Prep to try again.", notificationText())
    }

    @Test fun `timed out import is not retried`() = runTest {
        env.onSlow("POST", "/recipes/share", seconds = 4)     // TestEnv's long-call read timeout is 2 s
        val r = worker(link).doWork() as Result.Failure
        assertTrue(r.outputData.getString(ImportWorker.ERROR)!!.contains("taking too long"))
        assertFalse(r.outputData.getBoolean(ImportWorker.RETRY_SAFE, true))
    }

    @Test fun `server's import failure is a plain sentence, not the exception name`() = runTest {
        env.on("POST", "/recipes/share", code = 502, body = """{"detail":"import failed: NotARecipe: no Recipe JSON-LD"}""")
        val r = worker(link).doWork() as Result.Failure
        assertEquals("Couldn't read that recipe. Try again later.", r.outputData.getString(ImportWorker.ERROR))
        assertEquals("Couldn't read that recipe. Try again later. Open Meal Prep to try again.", notificationText())
    }

    @Test fun `a refusal that is already a sentence is shown as is`() = runTest {
        env.on("POST", "/recipes/share", code = 422, body = """{"detail":"no NYT Cooking link found"}""")
        val r = worker(link).doWork() as Result.Failure
        assertEquals("no NYT Cooking link found", r.outputData.getString(ImportWorker.ERROR))
        assertEquals("no NYT Cooking link found. Open Meal Prep to try again.", notificationText())
    }

    @Test fun `a timed-out failure's notification warns instead of inviting a retry`() = runTest {
        env.onSlow("POST", "/recipes/share", seconds = 4)
        assertTrue(worker(link).doWork() is Result.Failure)
        val text = notificationText()
        assertTrue(text, text.endsWith("check again in a minute. It may have been added already — check the week."))
        assertFalse(text.contains("try again"))
    }

    @Test fun `a re-run after an interrupted send does not post again`() = runTest {
        val gate = CountDownLatch(1)
        env.onGated("POST", "/recipes/share", gate, fixture("share_result.json"))
        val id = UUID.randomUUID()
        val first = launch(Dispatchers.Default) { worker(link, id = id).doWork() }
        env.awaitBody("POST", "/recipes/share")
        first.cancelAndJoin()                       // WorkManager stopped the worker mid-request
        gate.countDown()
        val again = worker(link, attempt = 1, id = id).doWork() as Result.Failure
        assertEquals(1, env.count("POST", "/recipes/share"))
        assertFalse(again.outputData.getBoolean(ImportWorker.RETRY_SAFE, true))
        assertTrue(notificationText().contains("It may have been added already — check the week."))
    }

    @Test fun `an unreachable attempt is still re-sent on retry`() = runTest {
        env.on("POST", "/recipes/share", body = fixture("share_result.json"))
        val id = UUID.randomUUID()
        env.offline = true
        assertTrue(worker(link, attempt = 0, id = id).doWork() is Result.Retry)
        env.offline = false
        assertTrue(worker(link, attempt = 1, id = id).doWork() is Result.Success)
        assertEquals(1, env.count("POST", "/recipes/share"))
    }

    @Test fun `incomplete input fails with a message instead of crashing`() = runTest {
        val r = worker(workDataOf(ImportWorker.KIND to ImportWorker.LINK)).doWork() as Result.Failure
        assertEquals("The shared link was incomplete.", r.outputData.getString(ImportWorker.ERROR))
        assertEquals(1, notifications().size)
    }

    private fun batch(name: String, vararg pages: String) = tmp.newFolder(name).also { dir ->
        pages.forEachIndexed { i, text -> File(dir, "page%02d.jpg".format(i + 1)).writeText(text) }
    }

    @Test fun `photo pages upload in order and are deleted after success`() = runTest {
        env.on("POST", "/recipes/photo", body = fixture("share_result.json"))
        val dir = batch("batch", "first", "second")
        val r = worker(ImportQueue.photoInput(dir, LocalDate.parse("2026-10-11"), null)).doWork()
        assertTrue(r is Result.Success)
        assertEquals(191, (r as Result.Success).outputData.getInt(ImportWorker.OUT_MISSING_PAGE, 0))
        val body = env.bodies("POST", "/recipes/photo").single()
        assertTrue(body.indexOf("first") in 0 until body.indexOf("second"))
        assertFalse(body.contains("name=\"title\""))            // no title: the part is left out
        assertFalse(dir.exists())
    }

    @Test fun `unreadable photo keeps the pages for Try again`() = runTest {
        env.on("POST", "/recipes/photo", code = 502, body = """{"detail":"couldn't read recipe: AIError: no ingredients"}""")
        val dir = batch("batch2", "x")
        val r = worker(ImportQueue.photoInput(dir, LocalDate.parse("2026-10-11"), "Lemon Bars")).doWork() as Result.Failure
        assertEquals(ImportWorker.PHOTO_UNREADABLE, r.outputData.getString(ImportWorker.ERROR))
        assertTrue(r.outputData.getBoolean(ImportWorker.RETRY_SAFE, false))     // the server saved nothing
        assertEquals(dir.path, r.outputData.getString(ImportWorker.DIR))         // "Try again" re-sends the same pages
        assertTrue(File(dir, "page01.jpg").exists())
    }

    @Test fun `a timed-out photo import is not re-sent and keeps its pages`() = runTest {
        env.onSlow("POST", "/recipes/photo", seconds = 4)     // TestEnv's long-call read timeout is 2 s
        val dir = batch("batch3", "x")
        val id = UUID.randomUUID()
        val r = worker(ImportQueue.photoInput(dir, LocalDate.parse("2026-10-11"), null), id = id).doWork() as Result.Failure
        assertFalse(r.outputData.getBoolean(ImportWorker.RETRY_SAFE, true))
        assertTrue(File(dir, "page01.jpg").exists())
        // WorkManager running the same job again must not post the photos twice.
        assertTrue(worker(ImportQueue.photoInput(dir, LocalDate.parse("2026-10-11"), null), attempt = 1, id = id).doWork() is Result.Failure)
        assertEquals(1, env.count("POST", "/recipes/photo"))
    }

    @Test fun `photos deleted from under the job fail with a message`() = runTest {
        val dir = tmp.newFolder("empty")
        val r = worker(ImportQueue.photoInput(dir, LocalDate.parse("2026-10-11"), null)).doWork() as Result.Failure
        assertEquals(ImportWorker.PAGES_GONE, r.outputData.getString(ImportWorker.ERROR))
        assertEquals(0, env.count("POST", "/recipes/photo"))
    }

    @Test fun `waiting for the home network keeps the pages and retries`() = runTest {
        env.offline = true
        val dir = batch("batch4", "x")
        assertTrue(worker(ImportQueue.photoInput(dir, LocalDate.parse("2026-10-11"), null)).doWork() is Result.Retry)
        assertTrue(File(dir, "page01.jpg").exists())
    }

    // Recipe 3 before and after its page-191 line (index 1) is attached; synthetic sub-recipe lines.
    private val beforeAttach = fixture("share_result.json").let { it.substring(it.indexOf("{", 1), it.indexOf(",\n \"entry\"")) }
    private val afterAttach = """{"id":3,"title":"Thai Green Curry Wings","source":"photo","steps":[],
        "ingredients":[{"raw":"24 chicken wings","name":"chicken wing"},
                       {"raw":"Batter for 24 crêpes, page 191","name":"crêpe batter","expanded":true,"sub_recipe":"Crêpe batter"},
                       {"raw":"2 eggs","name":"egg","qty":2.0,"sub_recipe":"Crêpe batter"}]}"""

    @Test fun `a referenced page is attached to its line`() = runTest {
        env.on("GET", "/recipes/3", body = beforeAttach)
        env.on("POST", "/recipes/3/pages", body = afterAttach)
        val dir = batch("ref", "p191")
        val r = worker(ImportQueue.pagesInput(dir, recipeId = 3, forLine = 1, page = 191)).doWork()
        val out = (r as Result.Success).outputData
        assertEquals(ImportWorker.PAGES, out.getString(ImportWorker.KIND))
        assertEquals(-1, out.getInt(ImportWorker.OUT_MISSING_LINE, 0))
        val body = env.bodies("POST", "/recipes/3/pages").single()
        assertTrue(body.contains("name=\"for_line\"") && body.contains("\r\n\r\n1\r\n") && body.contains("p191"))
        val n = notifications().single()
        assertEquals("Added the page to Thai Green Curry Wings", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Its ingredients are on the shopping list now.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(dir.exists())
    }

    @Test fun `a stale line number is corrected from the page before sending`() = runTest {
        // Line 1 was attached meanwhile (2 lines inserted); the page-194 line moved from 2 to 3.
        env.on("GET", "/recipes/3", body = afterAttach.replace("]}", ""","""
            + "{\"raw\":\"Orange butter, page 194\",\"name\":\"orange butter\",\"ref_page\":194}]}"))
        env.on("POST", "/recipes/3/pages", body = afterAttach)
        val dir = batch("ref3", "p194")
        assertTrue(worker(ImportQueue.pagesInput(dir, 3, forLine = 2, page = 194)).doWork() is Result.Success)
        assertTrue(env.bodies("POST", "/recipes/3/pages").single().contains("\r\n\r\n3\r\n"))
    }

    @Test fun `already attached page says so without sending`() = runTest {
        env.on("GET", "/recipes/3", body = afterAttach)
        val dir = batch("ref2", "x")
        val r = worker(ImportQueue.pagesInput(dir, 3, 1, 191)).doWork() as Result.Failure
        assertEquals(ImportWorker.ALREADY_ATTACHED, r.outputData.getString(ImportWorker.ERROR))
        assertFalse(r.outputData.getBoolean(ImportWorker.RETRY_SAFE, true))
        assertEquals(0, env.count("POST", "/recipes/3/pages"))
        assertEquals("Couldn't add the page", notifications().single().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun `the server's already-attached answer is a sentence, not retried`() = runTest {
        env.on("GET", "/recipes/3", body = beforeAttach)
        env.on("POST", "/recipes/3/pages", code = 409, body = """{"detail":"line 1 already has its sub-recipe attached"}""")
        val r = worker(ImportQueue.pagesInput(batch("ref4", "x"), 3, 1, 191)).doWork() as Result.Failure
        assertEquals(ImportWorker.ALREADY_ATTACHED, r.outputData.getString(ImportWorker.ERROR))
        assertFalse(r.outputData.getBoolean(ImportWorker.RETRY_SAFE, true))
        assertEquals("That page is already part of the recipe. Check the shopping list for its ingredients before adding it again.",
            notificationText())
    }

    @Test fun `an unreadable page keeps the photo for Try again`() = runTest {
        env.on("GET", "/recipes/3", body = beforeAttach)
        env.on("POST", "/recipes/3/pages", code = 502, body = """{"detail":"couldn't read the sub-recipe: AIError: x"}""")
        val dir = batch("ref5", "x")
        val r = worker(ImportQueue.pagesInput(dir, 3, 1, 191)).doWork() as Result.Failure
        assertEquals(ImportWorker.PAGE_UNREADABLE, r.outputData.getString(ImportWorker.ERROR))
        assertTrue(r.outputData.getBoolean(ImportWorker.RETRY_SAFE, false))
        assertTrue(File(dir, "page01.jpg").exists())
    }

    @Test fun `an interrupted page upload is not sent twice`() = runTest {
        env.on("GET", "/recipes/3", body = beforeAttach)
        env.onSlow("POST", "/recipes/3/pages", seconds = 4)
        val id = UUID.randomUUID()
        val dir = batch("ref6", "x")
        assertTrue(worker(ImportQueue.pagesInput(dir, 3, 1, 191), id = id).doWork() is Result.Failure)
        assertTrue(worker(ImportQueue.pagesInput(dir, 3, 1, 191), attempt = 1, id = id).doWork() is Result.Failure)
        assertEquals(1, env.count("POST", "/recipes/3/pages"))
    }

    @Test fun `after a page, the next missing one is offered`() = runTest {
        env.on("GET", "/recipes/3", body = beforeAttach)
        env.on("POST", "/recipes/3/pages", body = afterAttach.replace("]}", ""","""
            + "{\"raw\":\"Orange butter, page 194\",\"name\":\"orange butter\",\"ref_page\":194}]}"))
        val out = (worker(ImportQueue.pagesInput(batch("ref7", "x"), 3, 1, 191)).doWork() as Result.Success).outputData
        assertEquals(3, out.getInt(ImportWorker.OUT_MISSING_LINE, -1))
        assertEquals(194, out.getInt(ImportWorker.OUT_MISSING_PAGE, 0))
        assertEquals("Orange butter, page 194", out.getString(ImportWorker.OUT_MISSING_TEXT))
        assertTrue(notificationText().contains("page 194"))
    }
}
