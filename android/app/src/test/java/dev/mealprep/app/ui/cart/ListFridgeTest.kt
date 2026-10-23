package dev.mealprep.app.ui.cart

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.fixture
import dev.mealprep.app.work.JobWatcher
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The shopping list with the fridge (0.14.0): the switch, its default, the line texts and the cart line's note. */
@RunWith(RobolectricTestRunner::class)
class ListFridgeTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-10-07")
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(env.context)
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        env.on("POST", "/list", body = fixture("list.json"))
        env.on("GET", "/fridge", body = fixture("fridge.json"))
    }
    @After fun tearDown() = env.close()

    private fun vm(vararg weeks: String) = ListViewModel(env.repo, JobWatcher(wm), weeks.map(LocalDate::parse), today = { today })

    @Test fun `on by default for this week or next, off for later weeks, and a choice is remembered`() = runTest {
        val next = vm("2026-10-11").state.await { !it.loading }
        assertTrue(next.useFridge); assertEquals(4, next.fridgeCount)
        assertTrue(env.bodies("POST", "/list").last().endsWith(""""fridge":true}"""))
        val later = vm("2026-10-25").state.await { !it.loading }
        assertFalse(later.useFridge)
        assertTrue(env.bodies("POST", "/list").last().endsWith(""""fridge":false}"""))
        val v = vm("2026-10-11"); v.state.await { !it.loading }
        v.toggleFridge()
        v.state.await { !it.loading && !it.useFridge }
        assertTrue(env.bodies("POST", "/list").last().endsWith(""""fridge":false}"""))
        assertFalse(vm("2026-10-11").state.await { !it.loading }.useFridge)          // remembered on this phone
    }

    @Test fun `an empty fridge has no switch and asks for nothing`() = runTest {
        env.on("GET", "/fridge", body = "[]")
        val s = vm("2026-10-11").state.await { !it.loading }
        assertEquals(0, s.fridgeCount)
        assertTrue(env.bodies("POST", "/list").last().endsWith(""""fridge":false}"""))
    }

    private val partial = ListItem("egg|each", "egg", qty = 6.0, recipes = listOf("Quiche"), fridge = "partial", fridgeQty = 6.0)
    private val covered = ListItem("whole milk|vol", "whole milk", qty = 1.0, unit = "cup", needed = false, fridge = "covered", fridgeQty = 1.0)
    private val check = ListItem("cilantro|each", "cilantro", qty = 1.0, needed = false, fridge = "check")

    @Test fun `line texts say what the fridge covers, in words`() {
        assertEquals("Need 6 more · 6 from the fridge", fridgeText(partial))
        assertEquals("From the fridge", fridgeText(covered))
        assertEquals("From the fridge — check you have enough", fridgeText(check))
        val s = ListState(items = listOf(partial, covered, check), loading = false)
        assertEquals(listOf("egg"), s.toBuy.map { it.name })
        assertEquals(listOf("whole milk", "cilantro"), s.probablyHave.map { it.name })
        assertEquals(1, s.neededCount)
        assertEquals("Need 1 cup · 6 cup from the fridge", needText(DraftLine(1, name = "egg", qty = 1.0, unit = "cup", fridge = "partial", fridgeQty = 6.0)))
        assertEquals("Need 1 cup", needText(DraftLine(1, name = "egg", qty = 1.0, unit = "cup")))
    }

    @Test fun `the switch is one control and a covered line can still be ticked`() {
        var flips = 0; val toggled = mutableListOf<String>()
        compose.setContent {
            ListContent(ListState(weeks = setOf(LocalDate.parse("2026-10-11")), loading = false, items = listOf(partial, covered, check),
                fridgeCount = 4, useFridge = true), onWeek = {}, onToggle = { toggled += it }, onBuild = {}, today = today,
                onFridge = { flips++ })
        }
        compose.onNode(hasText(USE_FRIDGE) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch), useUnmergedTree = false)
            .assertIsOn().performClick()
        compose.onNodeWithText("From the fridge — check you have enough").assertExists()
        compose.onNodeWithText("1 cup whole milk").performClick()
        assertEquals(1, flips); assertEquals(listOf("whole milk|vol"), toggled)
    }
}
