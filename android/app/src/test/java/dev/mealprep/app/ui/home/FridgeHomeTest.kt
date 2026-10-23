package dev.mealprep.app.ui.home

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.FridgeNight
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.NightUse
import dev.mealprep.app.data.api.PendingRating
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.UsedAnswer
import dev.mealprep.app.data.api.UsedChange
import dev.mealprep.app.fixture
import dev.mealprep.app.work.ImportQueue
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** This week with the fridge (0.14.0): "Use up the fridge" and the morning-after nudge. */
@RunWith(RobolectricTestRunner::class)
class FridgeHomeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-10-07")   // Wednesday
    private val wk = LocalDate.parse("2026-10-04")
    private fun e(day: Int?) = PlanEntry(day ?: 9, "2026-10-04", 1, day)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun tearDown() = env.close()

    @Test fun `use up the fridge shows for a week with free nights and something in the fridge`() {
        assertEquals("Use up the fridge · 4 nights free", useUpFridge(wk, today, emptyList(), 3))        // Wed–Sat
        assertEquals("Use up the fridge · 3 nights free", useUpFridge(wk, today, listOf(e(4), e(1), e(null)), 3))
        assertEquals("Use up the fridge · 7 nights free", useUpFridge(wk.plusWeeks(1), today, emptyList(), 1))
        assertEquals("Use up the fridge · 1 night free", useUpFridge(wk, today, listOf(e(3), e(4), e(5)), 1))
        assertNull(useUpFridge(wk, today, listOf(e(3), e(4), e(5), e(6)), 3))                            // no free night left
        assertNull(useUpFridge(wk, today, emptyList(), 0))                                               // empty fridge
        assertNull(useUpFridge(wk.minusWeeks(1), today, emptyList(), 3))                                 // a past week
    }

    private val night = Http.json.decodeFromString(ListSerializer(FridgeNight.serializer()), fixture("fridge_used.json")).single()
    private val pending = PendingRating(21, "2026-10-04", 2, "2026-10-06", 3, "Chili")

    @Test fun `nudge texts`() {
        assertEquals("Update the fridge? Tuesday's Test Spinach Frittata used 4 Eggs and 1 cup Whole milk.", fridgeNudgeText(night))
        assertEquals("Tuesday's Salsa used what's in the fridge.", fridgeNudgeText(FridgeNight(1, "2026-10-06", "Salsa", check = listOf("Cilantro"))))
        assertEquals("Fridge updated · Eggs 2, Whole milk 3 cups", fridgeUpdatedText(UsedAnswer(31, "applied",
            listOf(UsedChange(1, "Eggs", text = "Eggs 2"), UsedChange(5, "Whole milk", text = "Whole milk 3 cups")))))
        assertEquals("a, b and c", andList(listOf("a", "b", "c")))
    }

    private fun banner(p: PendingRating?, n: FridgeNight?, sending: Boolean = false, calls: MutableList<String> = mutableListOf()) =
        compose.setContent {
            MorningBanner(p, n, sending, onRate = { calls += "rate" }, onLater = { calls += "later" }, onUpdate = { calls += "update" },
                onNotNow = { calls += "notnow" }, onFridge = { calls += "fridge" })
        }

    @Test fun `rating only`() {
        banner(pending, null)
        compose.onNodeWithText("Rate it").assertExists(); compose.onNodeWithText(UPDATE_FRIDGE).assertDoesNotExist()
    }

    @Test fun `fridge only, both, and check only`() {
        val calls = mutableListOf<String>()
        banner(pending, night, calls = calls)
        compose.onNodeWithText("Rate it").assertExists()
        compose.onNodeWithText("Check: Cilantro").assertExists()
        compose.onNodeWithText(UPDATE_FRIDGE).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Open the fridge").performClick()
        assertEquals(listOf("update", "notnow", "fridge"), calls)
    }

    @Test fun `check only has no update button`() {
        banner(null, FridgeNight(1, "2026-10-06", "Salsa", check = listOf("Cilantro")))
        compose.onNodeWithText(UPDATE_FRIDGE).assertDoesNotExist()
        compose.onNodeWithText("Open the fridge").assertExists()
    }

    @Test fun `while sending the buttons are off`() {
        banner(null, night, sending = true)
        compose.onNodeWithText(UPDATE_FRIDGE).assertIsNotEnabled()
    }

    @Test fun `update fridge applies once even on a double tap, then the nudge goes`() = runTest {
        env.on("GET", "/fridge/used", body = fixture("fridge_used.json"))
        env.on("GET", "/fridge", body = fixture("fridge.json"))
        env.on("GET", "/ratings/pending", body = "[]")
        val gate = java.util.concurrent.CountDownLatch(1)
        env.onGated("POST", "/fridge/used/31", gate, """{"entry_id":31,"action":"applied","changes":[{"item_id":1,"name":"Eggs","text":"Eggs 2"}]}""")
        val vm = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { today })
        vm.nudge.await { it != null }
        assertEquals(4, vm.fridgeCount.value)
        vm.answerNudge(true); vm.answerNudge(true)
        withContext(Dispatchers.Default) { delay(200) }
        gate.countDown()
        assertEquals("Fridge updated · Eggs 2", vm.snack.await { it != null })
        assertNull(vm.nudge.value)
        assertEquals(1, env.count("POST", "/fridge/used/31"))
        assertEquals("""{"apply":true}""", env.bodies("POST", "/fridge/used/31").single())
    }

    @Test fun `not now dismisses on the server`() = runTest {
        env.on("GET", "/fridge/used", body = fixture("fridge_used.json"))
        env.on("GET", "/ratings/pending", body = "[]")
        env.on("POST", "/fridge/used/31", body = """{"entry_id":31,"action":"dismissed"}""")
        val vm = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { today })
        vm.nudge.await { it != null }
        vm.answerNudge(false)
        vm.nudge.await { it == null }
        assertEquals("""{"apply":false}""", env.awaitBody("POST", "/fridge/used/31"))
        assertNull(vm.snack.value)
    }
}
