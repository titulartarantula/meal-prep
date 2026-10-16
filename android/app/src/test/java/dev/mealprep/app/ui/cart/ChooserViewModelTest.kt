package dev.mealprep.app.ui.cart

import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.fixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChooserViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private val choicesPath = "/drafts/7/lines/31/choices"
    private fun queries() = env.requests.filter { it.url.encodedPath == choicesPath }
        .map { it.url.queryParameter("term") to it.url.queryParameter("page") }

    /** Line 31 (onion) of draft 7, whose product is changed to the fixture's current cheddar. */
    private suspend fun open(): ChooserViewModel {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json")
            .replace("\"code\": \"20600927001_KG\"", "\"code\": \"21290257_EA\"").replace("\"quantity\": 1,", "\"quantity\": 3,"))
        env.on("GET", choicesPath, body = fixture("choices.json"))
        return ChooserViewModel(env.repo, 7, 31).also { vm -> vm.state.await { !it.loading } }
    }

    @Test fun `opens on the item name, page 0, in the server's order`() = runTest {
        val vm = open()
        val s = vm.state.value
        assertEquals(listOf(null to "0"), queries())                       // no term = the line's item name
        assertEquals("onion", s.line!!.name)
        assertTrue(s.editable)
        assertEquals("old cheddar", s.shownTerm)
        assertEquals("21290257_EA", s.choices.first().product.code)
        assertEquals("21615428_EA", s.choices.last().product.code)          // out of stock last
        assertTrue(s.hasMore)
    }

    @Test fun `Show more asks for the next page and appends it without repeats`() = runTest {
        val vm = open()
        env.on("GET", choicesPath, body = """{"term":"old cheddar","page":1,"total":177,"has_more":false,"choices":[
            {"product":{"code":"21495455_EA","name":"Extra Old Cheddar Cheese"},"group":"other"},
            {"product":{"code":"P1","name":"Old Cheddar Cheese Slices"},"group":"other"}]}""")
        vm.showMore()
        val s = vm.state.await { it.page == 1 }
        assertEquals(listOf(null to "0", null to "1"), queries())
        assertEquals(7, s.choices.size)
        assertEquals("P1", s.choices[5].product.code)                      // before the out-of-stock one
        assertFalse(s.hasMore)
        vm.showMore()                                                       // nothing more: no request
        assertEquals(2, queries().size)
    }

    @Test fun `a search starts again at page 0 with the term, blank goes back to the item`() = runTest {
        val vm = open()
        vm.search("  aged cheddar ")
        vm.state.await { !it.loading && queries().size == 2 }
        assertEquals("aged cheddar" to "0", queries().last())
        vm.search(" ")
        vm.state.await { !it.loading && queries().size == 3 }
        assertEquals(null to "0", queries().last())
    }

    @Test fun `filters narrow what is shown, not what was loaded`() = runTest {
        val vm = open()
        vm.toggle(ChoiceFilter.SALE)
        assertEquals(listOf("21289751_EA"), vm.state.value.shown.map { it.product.code })
        assertEquals(6, vm.state.value.choices.size)
        vm.toggle(ChoiceFilter.SALE)
        assertEquals(6, vm.state.value.shown.size)
    }

    @Test fun `the current product opens at the line's quantity, others at their suggestion, out of stock not at all`() = runTest {
        val vm = open()
        val cs = vm.state.value.choices
        vm.open(cs[0])
        assertEquals(3, vm.state.value.detail!!.quantity)
        vm.open(cs[2])
        assertEquals(2, vm.state.value.detail!!.quantity)
        vm.closeDetail()
        vm.open(cs.last())
        assertNull(vm.state.value.detail)
    }

    @Test fun `Use this sends product, quantity and pin in one PATCH, then is done`() = runTest {
        val vm = open()
        env.on("PATCH", "/drafts/7/lines/31", body = """{"id":31,"name":"onion","quantity":3,"source":"user","pinned":true,
            "product":{"code":"21289751_EA","name":"Old Cheddar Cheese","price":4.88}}""")
        vm.open(vm.state.value.choices.first { it.product.code == "21289751_EA" })
        vm.more()
        vm.setPin(true)
        vm.use()
        val s = vm.state.await { it.done }
        assertEquals("""{"product_code":"21289751_EA","quantity":3,"pinned":true}""", env.bodies("PATCH", "/drafts/7/lines/31").single())
        assertTrue(s.line!!.pinned)
    }

    @Test fun `Use this on the current product sends only what changed, nothing when nothing did`() = runTest {
        val vm = open()
        vm.open(vm.state.value.choices[0])
        vm.use()
        assertTrue(vm.state.await { it.done }.done)
        assertEquals(0, env.count("PATCH", "/drafts/7/lines/31"))

        val vm2 = open()
        env.on("PATCH", "/drafts/7/lines/31", body = """{"id":31,"name":"onion","quantity":2,"product":{"code":"21290257_EA"}}""")
        vm2.open(vm2.state.value.choices[0])
        vm2.fewer()
        vm2.use()
        vm2.state.await { it.done }
        assertEquals("""{"quantity":2}""", env.bodies("PATCH", "/drafts/7/lines/31").single())
    }

    @Test fun `a cart changed elsewhere refuses Use this and says so`() = runTest {
        val vm = open()
        env.on("PATCH", "/drafts/7/lines/31", code = 409, body = """{"detail":"draft 7 is sent, not ready"}""")
        vm.open(vm.state.value.choices[2])
        vm.use()
        val s = vm.state.await { it.error != null }
        assertEquals(DraftViewModel.CHANGED_HERE, s.error)
        assertFalse(s.editable)
        assertFalse(s.done)
    }

    @Test fun `a sent cart opens read-only`() = runTest {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json").replace("\"status\": \"ready\"", "\"status\": \"sent\""))
        env.on("GET", choicesPath, body = fixture("choices.json"))
        val vm = ChooserViewModel(env.repo, 7, 31)
        assertFalse(vm.state.await { !it.loading }.editable)
        vm.open(vm.state.value.choices[2])
        vm.use()
        assertFalse(vm.state.value.done)
        assertEquals(0, env.count("PATCH", "/drafts/7/lines/31"))
    }

    @Test fun `a Loblaws outage says so and Try again reloads`() = runTest {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json"))
        env.on("GET", choicesPath, code = 502, body = """{"detail":"PC Express search failed: ConnectError"}""")
        val vm = ChooserViewModel(env.repo, 7, 31)
        assertEquals("Loblaws didn't answer just now. Try again in a minute.", vm.state.await { !it.loading }.error)
        env.on("GET", choicesPath, body = fixture("choices.json"))
        vm.start()
        assertEquals(6, vm.state.await { !it.loading && it.error == null }.choices.size)
    }

    @Test fun `a line gone from the cart says so`() = runTest {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json"))
        val vm = ChooserViewModel(env.repo, 7, 99)
        assertEquals(ChooserViewModel.GONE, vm.state.await { !it.loading }.error)
    }
    // --- adding an item by search (0.13.0) ------------------------------------------------------------------------

    private fun searches() = env.requests.filter { it.url.encodedPath == "/products/choices" }
        .map { it.url.queryParameter("term") to it.url.queryParameter("page") }

    @Test fun `Add an item starts blank and searches nothing until something is typed`() = runTest {
        env.on("GET", "/products/choices", body = fixture("choices.json"))
        val vm = ChooserViewModel(env.repo, ChooserMode.AddToList(java.time.LocalDate.parse("2026-10-11")))
        val s0 = vm.state.value
        assertTrue(s0.adding && s0.blank && s0.editable && !s0.loading)
        vm.search("  ")
        assertTrue(searches().isEmpty())
        vm.search(" paper towels ")
        val s = vm.state.await { !it.loading && it.choices.isNotEmpty() }
        assertEquals(listOf("paper towels" to "0"), searches())
        assertFalse(s.blank)
        assertEquals(6, s.choices.size)
    }

    @Test fun `Add to list posts the week, the search, the product and how many`() = runTest {
        env.on("GET", "/products/choices", body = fixture("choices.json").replace("\"term\": \"old cheddar\"", "\"term\": \"cheddar\""))
        env.on("POST", "/extras", code = 201, body = """{"id":4,"week":"2026-10-11","term":"cheddar","quantity":2}""")
        val vm = ChooserViewModel(env.repo, ChooserMode.AddToList(java.time.LocalDate.parse("2026-10-14")))
        vm.search("cheddar")
        vm.state.await { !it.loading && it.choices.isNotEmpty() }
        vm.open(vm.state.value.choices.first { it.product.code == "21289751_EA" })
        vm.more()
        vm.use()
        vm.state.await { it.done }
        // the week the list chose (any day of it → its Sunday), the search as the item's name, the stepper's 3
        assertEquals("""{"week":"2026-10-11","term":"cheddar","product_code":"21289751_EA","quantity":3}""",
            env.bodies("POST", "/extras").single())
    }

    @Test fun `Add to cart checks the cart is open and posts a line`() = runTest {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json"))
        env.on("GET", "/products/choices", body = fixture("choices.json"))
        env.on("POST", "/drafts/7/lines", code = 201, body = """{"id":40,"name":"old cheddar","quantity":1,"extra_id":4}""")
        val vm = ChooserViewModel(env.repo, ChooserMode.AddToCart(7))
        assertTrue(vm.state.await { !it.loading }.editable)
        vm.search("old cheddar")
        vm.state.await { !it.loading && it.choices.isNotEmpty() }
        vm.open(vm.state.value.choices.first { it.product.code == "21289751_EA" })
        vm.use()
        vm.state.await { it.done }
        assertEquals("""{"term":"old cheddar","product_code":"21289751_EA","quantity":2}""", env.bodies("POST", "/drafts/7/lines").single())
        assertEquals(0, env.count("PATCH", "/drafts/7/lines/31"))
    }

    @Test fun `Add to cart on a sent cart can't add`() = runTest {
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json").replace("\"status\": \"ready\"", "\"status\": \"sent\""))
        env.on("GET", "/products/choices", body = fixture("choices.json"))
        val vm = ChooserViewModel(env.repo, ChooserMode.AddToCart(7))
        assertFalse(vm.state.await { !it.loading }.editable)
        vm.search("cheddar")
        vm.state.await { !it.loading && it.choices.isNotEmpty() }
        vm.open(vm.state.value.choices[2])
        vm.use()
        assertFalse(vm.state.value.done)
        assertEquals(0, env.count("POST", "/drafts/7/lines"))
    }
}
