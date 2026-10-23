package dev.mealprep.app.ui.fridge

import androidx.lifecycle.SavedStateHandle
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.fixture
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeReviewViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val env = TestEnv()
    private val ready = fixture("fridge_read_ready.json")
    @After fun tearDown() = env.close()

    private suspend fun vm(saved: SavedStateHandle = SavedStateHandle(), resend: (File) -> Unit = {}) =
        FridgeReviewViewModel(env.repo, 12, saved, resend, pollMs = 10).also { v -> v.state.await { it.read?.status == "ready" } }

    @Test fun `waits for a read in progress, then ticks the defaults`() = runTest {
        env.onSequence("GET", "/fridge/reads/12", listOf(ready.replace("\"ready\"", "\"reading\""), ready))
        val v = vm()
        assertEquals(setOf("p1", "p2", "p3"), v.state.value.ticks)
    }

    @Test fun `ticks and edits survive the process and saving sends only the decisions`() = runTest {
        env.on("GET", "/fridge/reads/12", body = ready)
        env.on("POST", "/fridge/reads/12/apply", body = ready.replace("\"ready\"", "\"applied\"").replace("\"result\":null", "\"result\":{\"added\":1,\"changed\":1,\"seen\":1,\"removed\":1}"))
        env.on("GET", "/fridge", body = "[]")
        val saved = SavedStateHandle()
        val first = vm(saved)
        first.toggle("p5"); first.edit("p1", LineEdit(qty = 5.0, amountEdited = true))
        val again = vm(saved)                                        // after a process death
        assertEquals(setOf("p1", "p2", "p3", "p5"), again.state.value.ticks)
        assertEquals(5.0, again.state.value.edits["p1"]!!.qty)
        val dir = tmp.newFolder("batch").also { File(it, "page01.jpg").writeText("x") }
        env.repo.rememberFridgeBatch(12, dir)
        again.save(); again.save()                                    // a double tap sends once
        assertEquals("Saved: 1 added, 1 changed, 1 still there, 1 removed.", again.state.await { it.done != null }.done)
        assertEquals(1, env.count("POST", "/fridge/reads/12/apply"))
        assertEquals("""{"lines":[{"pid":"p1","accept":true,"qty":5.0},{"pid":"p2","accept":true},{"pid":"p3","accept":true},{"pid":"p4","accept":false},{"pid":"p5","accept":true}]}""",
            env.bodies("POST", "/fridge/reads/12/apply").single())
        assertFalse(dir.exists())                                     // the photos are no longer needed
    }

    @Test fun `saved on the other phone closes as done`() = runTest {
        env.on("GET", "/fridge/reads/12", body = ready)
        env.on("POST", "/fridge/reads/12/apply", code = 409, body = """{"detail":"These changes were already saved (on the other phone?).","read":{"id":12,"status":"applied"}}""")
        env.on("GET", "/fridge", body = "[]")
        val v = vm()
        v.save()
        assertEquals(FridgeReviewViewModel.ALREADY_SAVED, v.state.await { it.done != null }.done)
        assertTrue(env.count("GET", "/fridge") >= 1)
    }

    @Test fun `offline the save says so and the ticks stay`() = runTest {
        env.on("GET", "/fridge/reads/12", body = ready)
        val v = vm()
        env.offline = true
        v.save()
        val s = v.state.await { it.message != null }
        assertEquals(FridgeLogic.OFFLINE, s.message); assertEquals(setOf("p1", "p2", "p3"), s.ticks); assertFalse(s.saving)
    }

    @Test fun `discard dismisses, a failed read can be sent again`() = runTest {
        env.on("GET", "/fridge/reads/12", body = ready)
        env.on("POST", "/fridge/reads/12/dismiss", body = ready.replace("\"ready\"", "\"dismissed\""))
        env.on("GET", "/fridge", body = "[]")
        val v = vm()
        v.discard()
        assertEquals(FridgeReviewViewModel.DISCARDED, v.state.await { it.done != null }.done)

        env.on("GET", "/fridge/reads/12", body = """{"id":12,"status":"failed","photo_count":2,"error":"Couldn't read the fridge in those photos."}""")
        val dir = tmp.newFolder("again").also { File(it, "page01.jpg").writeText("x") }
        env.repo.rememberFridgeBatch(12, dir)
        val sent = mutableListOf<File>()
        val f = FridgeReviewViewModel(env.repo, 12, SavedStateHandle(), { sent += it }, pollMs = 10)
        f.state.await { it.canRetry }
        f.retry()
        assertEquals(FridgeReviewViewModel.SENT_AGAIN, f.state.await { it.done != null }.done)
        assertEquals(listOf(dir), sent)
    }
}
