package dev.mealprep.app.ui.home

import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.fixture
import dev.mealprep.app.work.ImportQueue
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** This week's night names and store items (2026-11-07): loaded with the week, each write sent as the server wants it. */
@RunWith(RobolectricTestRunner::class)
class NightViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    private val wk = LocalDate.parse("2026-10-12")
    private lateinit var vm: HomeViewModel
    private val pizza = """{"id":7,"week":"2026-10-12","day":4,"term":"frozen pizza","quantity":2,
        "product":{"code":"PIZ","name":"Pepperoni Pizza","brand":"Hearthstone"}}"""

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(env.context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        env.on("GET", "/weeks/2026-10-12", body = fixture("week_home.json"))
        env.on("GET", "/weeks", body = """[{"week":"2026-10-12","entries":3,"extras":1,"named_nights":1,"carted":false}]""")
        env.on("GET", "/weeks/2026-10-12/nights", body = """[{"day":1,"title":"Leftovers","items":[]},{"day":4,"title":"Pizza night","items":[$pizza]}]""")
        vm = HomeViewModel(env.repo, ImportQueue(WorkManager.getInstance(env.context)), today = { LocalDate.parse("2026-10-08") })
    }
    @After fun tearDown() = env.close()

    @Test fun `the week loads its night names and store items`() = runTest {
        val ui = vm.week(wk).await { !it.loading && it.nights.isNotEmpty() }
        assertEquals(listOf(1 to "Leftovers", 4 to "Pizza night"), ui.nights.map { it.day to it.title })
        assertEquals(listOf("Hearthstone Pepperoni Pizza"), ui.nights[1].items.map(::itemName))
    }

    @Test fun `a week with only store items builds a cart`() = runTest {
        env.on("GET", "/weeks/2026-10-12", body = "[]")
        val ui = vm.week(wk).await { !it.loading && it.nights.isNotEmpty() }
        assertEquals(ContextAction.BuildCart(wk), ui.action)
        assertEquals(StatusStrip(planned = true, cartSent = false), ui.strip)
    }

    @Test fun `naming, renaming and clearing a night`() = runTest {
        vm.week(wk).await { !it.loading && it.nights.isNotEmpty() }
        env.on("PUT", "/weeks/2026-10-12/nights/5", body = """{"week":"2026-10-12","day":5,"title":"Taco night"}""")
        vm.nameNight(wk, 5, "  Taco   night ")
        assertEquals("""{"title":"Taco   night"}""", env.awaitBody("PUT", "/weeks/2026-10-12/nights/5"))
        env.on("DELETE", "/weeks/2026-10-12/nights/1", code = 204)
        vm.nameNight(wk, 1, "  ")
        env.awaitBody("DELETE", "/weeks/2026-10-12/nights/1")
        assertEquals(1, env.count("DELETE", "/weeks/2026-10-12/nights/1"))
    }

    @Test fun `a store item's quantity, move and removal`() = runTest {
        val e = vm.week(wk).await { !it.loading && it.nights.isNotEmpty() }.nights[1].items.single()
        env.on("PATCH", "/extras/7", body = pizza.replace("\"quantity\":2", "\"quantity\":3"))
        vm.setItemQuantity(wk, e, 3)
        assertEquals("""{"quantity":3}""", env.awaitBody("PATCH", "/extras/7"))
        env.requests.clear()
        vm.moveItem(wk, e, 6)
        assertEquals("""{"day":6}""", env.awaitBody("PATCH", "/extras/7"))
        env.requests.clear()
        vm.moveItem(wk, e, null)                                              // the whole week: null sent explicitly
        assertEquals("""{"day":null}""", env.awaitBody("PATCH", "/extras/7"))
        env.on("DELETE", "/extras/7", code = 204)
        vm.removeItem(wk, e)
        env.awaitBody("DELETE", "/extras/7")
        assertEquals(1, env.count("DELETE", "/extras/7"))
    }
}
