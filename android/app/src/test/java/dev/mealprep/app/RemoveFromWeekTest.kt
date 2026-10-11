package dev.mealprep.app

import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.core.Weeks
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Remove from week, then straight to Recipes: the whole app against a fake server that answers like the real one
 *  (a recipe is "On the plan" while its plan entry exists). */
@RunWith(RobolectricTestRunner::class)
@Config(application = MealPrepApp::class, qualifiers = "w411dp-h891dp")
class RemoveFromWeekTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app: MealPrepApp = ApplicationProvider.getApplicationContext()
    private val env = TestEnv()
    private val week = Weeks.weekStart(LocalDate.now()).toString()
    private val deleted get() = env.count("DELETE", "/plan/21") > 0

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        env.onEach("GET", "/weeks/$week") {
            if (deleted) "[]" else """[{"id":21,"week":"$week","recipe_id":5,"day":0,"multiplier":1.0,"title":"Chili"}]"""
        }
        env.onEach("GET", "/weeks") { """[{"week":"$week","entries":${if (deleted) 0 else 1},"carted":false}]""" }
        env.on("GET", "/ratings/pending", body = "[]")
        env.on("DELETE", "/plan/21", code = 204)
        env.onEach("GET", "/recipes") {
            val planned = if (deleted) "" else "\"$week\""
            """[{"id":5,"title":"Chili","source":"nyt","source_kind":"nyt","planned_weeks":[$planned]}]"""
        }
        runBlocking {
            app.graph.settingsStore.update { it.copy(serverUrl = env.server.url("/").toString(), token = "tok", askedNotificationPermission = true) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { app.graph.settings.first { it.configured } } }
        }
    }

    @After fun tearDown() = env.close()

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** Waits (real time, ≤ 5 s) with the test clock stepped a frame at a time, so the Undo snackbar (10 s) stays up. */
    private fun until(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("$what; requests: ${env.requests.map { "${it.method} ${it.url}" }}")
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
        }
    }

    @Test fun `a dinner removed from the week is not on the plan in Recipes, even while Undo was still offered`() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { shown("Chili") }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("Chili").performClick()
            until("the dialog") { shown("Remove from week") }
            compose.onNodeWithText("Remove from week").performClick()
            until("the Undo snackbar") { shown("Took Chili off the week") }
            compose.onNode(hasText("Recipes") and isSelectable()).performClick()      // before the snackbar goes
            until("Recipes asked the server") { env.count("GET", "/recipes") > 0 && shown("Chili") }
            assertTrue("leaving This week makes the removal final", deleted)
            val order = env.requests.map { "${it.method} ${it.url.encodedPath}" }
            assertTrue("deleted before Recipes asked: $order", order.indexOf("DELETE /plan/21") < order.indexOf("GET /recipes"))
            compose.onNodeWithText("On the plan", substring = true).assertDoesNotExist()
            compose.onNode(hasText("This week") and isSelectable()).performClick()   // and Undo isn't offered for it any more
            until("This week again") { compose.onAllNodes(hasContentDescription("Previous week")).fetchSemanticsNodes().isNotEmpty() }
            repeat(30) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithText("Took Chili off the week").assertDoesNotExist()
            assertEquals(1, env.count("DELETE", "/plan/21"))
        }
    }
}
