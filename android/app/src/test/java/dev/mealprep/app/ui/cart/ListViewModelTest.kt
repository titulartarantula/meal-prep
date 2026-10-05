package dev.mealprep.app.ui.cart

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.fixture
import dev.mealprep.app.ui.common.WeekOption
import dev.mealprep.app.work.JobWatcher
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
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
class ListViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        wm = WorkManager.getInstance(env.context)
        env.on("GET", "/weeks", body = fixture("weeks.json"))
        env.on("POST", "/list", body = fixture("list.json"))
    }
    @After fun tearDown() = env.close()

    private fun vm(initial: List<String>) = ListViewModel(env.repo, JobWatcher(wm), initial.map(LocalDate::parse), today = { LocalDate.parse("2026-10-07") })

    @Test fun `no week given uses the server's next uncarted week`() = runTest {
        env.on("GET", "/cart/default-week", body = """{"week":"2026-10-18"}""")
        val s = vm(emptyList()).state.await { !it.loading }
        assertEquals(setOf(LocalDate.parse("2026-10-18")), s.weeks)
        assertEquals("""{"weeks":["2026-10-18"],"people":4}""", env.bodies("POST", "/list").single())
        assertEquals(listOf("onion"), s.toBuy.map { it.name })
        assertEquals(listOf("olive oil"), s.probablyHave.map { it.name })
        assertEquals(1, s.neededCount)
    }

    @Test fun `ticking an on-hand item and building sends every item with its tick`() = runTest {
        env.on("POST", "/drafts", code = 202, body = """{"id":7,"status":"building"}""")
        val vm = vm(listOf("2026-10-11"))
        vm.state.await { !it.loading }
        vm.toggle("olive oil|vol")
        vm.buildCart()
        assertEquals(7, vm.state.await { it.draftId != null }.draftId)
        val body = env.bodies("POST", "/drafts").single()
        assertTrue(body.contains(""""weeks":["2026-10-11"]"""))
        assertTrue(body.contains(""""key":"olive oil|vol","name":"olive oil","qty":2.0,"unit":"tbsp","likely_on_hand":true,"needed":true"""))
        assertEquals(1, wm.getWorkInfosForUniqueWork("watch-draft-7").get().size)
        assertFalse(vm.state.value.canBuild)                         // no second draft from a double tap
    }

    @Test fun `several weeks are combined, sorted`() = runTest {
        val vm = vm(listOf("2026-10-18"))
        vm.state.await { !it.loading }
        vm.toggleWeek(LocalDate.parse("2026-10-11"))
        vm.state.await { !it.loading && it.weeks.size == 2 }
        assertEquals("""{"weeks":["2026-10-11","2026-10-18"],"people":4}""", env.bodies("POST", "/list").last())
    }

    @Test fun `the last week can't be unticked`() = runTest {
        val vm = vm(listOf("2026-10-18"))
        vm.state.await { !it.loading }
        vm.toggleWeek(LocalDate.parse("2026-10-18"))
        assertEquals(setOf(LocalDate.parse("2026-10-18")), vm.state.value.weeks)
    }

    @Test fun `offline says so and builds nothing`() = runTest {
        env.offline = true
        val vm = vm(listOf("2026-10-11"))
        val s = vm.state.await { !it.loading }
        assertEquals("Can't reach the meal-prep server. Are you on home Wi-Fi (or WireGuard)?", s.error)
        assertFalse(s.canBuild)
        vm.buildCart()
        assertEquals(0, env.count("POST", "/drafts"))
    }

    @Test fun `an empty week says nothing is planned`() = runTest {
        env.on("POST", "/list", body = "[]")
        val s = vm(listOf("2026-10-25")).state.await { !it.loading }
        assertEquals("Nothing planned for that week yet.", s.error)
        assertFalse(s.canBuild)
    }

    @Test fun `build failure keeps the list and shows the reason`() = runTest {
        env.on("POST", "/drafts", code = 422, body = """{"detail":"no items to buy"}""")
        val vm = vm(listOf("2026-10-11"))
        vm.state.await { !it.loading }
        vm.buildCart()
        val s = vm.state.await { it.error != null }
        assertEquals("no items to buy", s.error)
        assertNull(s.draftId)
        assertTrue(s.canBuild)
        assertEquals(2, s.items.size)
    }

    @Test fun `a selected week outside the picker is still shown`() {
        val today = LocalDate.parse("2026-10-07")
        val far = LocalDate.parse("2027-01-03")
        val opts = listOf(WeekOption(LocalDate.parse("2026-10-04"), "This week", null))
        assertEquals(listOf(LocalDate.parse("2026-10-04"), far), withSelected(opts, setOf(far), today).map { it.week })
    }

    @Test fun `item text leaves the prep note for its own line`() {
        assertEquals("3 onion", itemText(ListItem("k", "onion", qty = 3.0, prep = "diced")))
        assertEquals("½ cup rice", itemText(ListItem("k", "rice", qty = 0.5, unit = "cup")))
        assertEquals("⅚ cup whole milk", itemText(ListItem("k", "whole milk", qty = 0.83, unit = "cup", prep = "warmed")))
        assertEquals("salt", itemText(ListItem("k", "salt")))
    }

    @Test fun `quantities read like a recipe`() {
        assertEquals("2", qty(2.0))
        assertEquals("⅓", qty(0.33))
        assertEquals("⅔", qty(0.67))
        assertEquals("1½", qty(1.5))
        assertEquals("1⅛", qty(1.12))
        assertEquals("1⅝", qty(1.62))
        assertEquals("0.9", qty(0.9))
        assertEquals("2.45", qty(2.45))
        assertEquals("3", qty(2.99))
        assertEquals("¼", amountText(0.25, null))
        assertEquals("1¼ lb", amountText(1.25, "lb"))
        assertEquals(null, amountText(null, "cup"))
    }
}
