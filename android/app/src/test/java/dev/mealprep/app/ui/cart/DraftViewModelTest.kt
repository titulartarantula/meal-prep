package dev.mealprep.app.ui.cart

import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.Product
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
class DraftViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private suspend fun ready(): DraftViewModel {
        env.onSequence("GET", "/drafts/7", listOf(fixture("draft_building.json"), fixture("draft_ready.json")))
        return DraftViewModel(env.repo, 7, pollMs = 10).also { vm -> vm.state.await { it.draft?.status == "ready" } }
    }

    @Test fun `total counts kept, matched lines`() {
        val p = Product("A", "x", price = 2.5)
        assertEquals(7.5, estimatedTotal(listOf(
            DraftLine(1, "a", "a", product = p, quantity = 3),
            DraftLine(2, "b", "b", product = p, quantity = 1, removed = true),
            DraftLine(3, "c", "c", product = null, quantity = 0),
            DraftLine(4, "d", "d", product = null, quantity = null),
        )), 0.001)
    }

    @Test fun `polls until ready`() = runTest {
        val vm = ready()
        assertEquals(2, vm.state.value.draft!!.lines.size)
        assertEquals(3.99, vm.state.value.total, 0.001)
        assertEquals(1, vm.state.value.itemCount)
        assertTrue(vm.state.value.canSend)
    }

    @Test fun `quantity zero removes the line`() = runTest {
        val vm = ready()
        env.on("PATCH", "/drafts/7/lines/31", body = """{"id":31,"item_key":"onion|each","name":"onion","quantity":0,"removed":true,
            "product":{"code":"20600927001_KG","name":"Yellow Onions","price":3.99}}""")
        vm.setQuantity(vm.state.value.draft!!.lines[0], 0)
        val s = vm.state.await { it.draft!!.lines[0].removed }
        assertEquals("""{"quantity":0}""", env.bodies("PATCH", "/drafts/7/lines/31").single())
        assertEquals(0.0, s.total, 0.001)
        assertFalse(s.canSend)                                       // nothing left to send
    }

    @Test fun `put back sends removed false`() = runTest {
        val vm = ready()
        env.on("PATCH", "/drafts/7/lines/31", body = """{"id":31,"name":"onion","quantity":1,"removed":false,
            "product":{"code":"20600927001_KG","name":"Yellow Onions","price":3.99}}""")
        vm.setRemoved(vm.state.value.draft!!.lines[0], false)
        vm.state.await { it.busyLine == null && env.count("PATCH", "/drafts/7/lines/31") == 1 }
        assertEquals("""{"removed":false}""", env.bodies("PATCH", "/drafts/7/lines/31").single())
    }

    @Test fun `free-text search then swap`() = runTest {
        val vm = ready()
        val beef = vm.state.value.draft!!.lines[1]
        env.on("POST", "/drafts/7/lines/32/search", body = """[{"code":"B1","name":"Lean Ground Beef","price":11.0,"stock":"OK"}]""")
        env.on("PATCH", "/drafts/7/lines/32", body = """{"id":32,"item_key":"ground beef|g","name":"ground beef","quantity":1,"source":"user",
            "product":{"code":"B1","name":"Lean Ground Beef","price":11.0}}""")
        vm.openSwap(beef)
        assertEquals("ground beef", vm.state.value.swap!!.term)
        vm.search("lean ground beef")
        val results = vm.state.await { it.swap?.results?.isNotEmpty() == true }.swap!!.results
        assertEquals("""{"term":"lean ground beef"}""", env.bodies("POST", "/drafts/7/lines/32/search").single())
        vm.choose(results.single())
        val s = vm.state.await { it.draft!!.lines[1].product?.code == "B1" }
        assertEquals("""{"product_code":"B1"}""", env.bodies("PATCH", "/drafts/7/lines/32").single())
        assertEquals(14.99, s.total, 0.001)
        assertEquals(null, s.swap)
    }

    @Test fun `swap opens with the line's alternatives`() = runTest {
        val vm = ready()
        vm.openSwap(vm.state.value.draft!!.lines[0])
        assertEquals(listOf("20107500001_KG"), vm.state.value.swap!!.results.map { it.code })
    }

    @Test fun `a search with no results says so`() = runTest {
        val vm = ready()
        env.on("POST", "/drafts/7/lines/32/search", body = "[]")
        vm.openSwap(vm.state.value.draft!!.lines[1])
        vm.search("unicorn steak")
        assertEquals("Nothing in stock for that — try other words.", vm.state.await { it.swap?.error != null }.swap!!.error)
    }

    @Test fun `send gives the cart id`() = runTest {
        val vm = ready()
        env.on("POST", "/drafts/7/send", body = """{"pcx_cart_id":"24b34302-2508-4795-ab5d-2f2f9a7de03c"}""")
        vm.send()
        assertEquals("24b34302-2508-4795-ab5d-2f2f9a7de03c", vm.state.await { it.sentCartId != null }.sentCartId)
        assertEquals("sent", vm.state.value.draft!!.status)
        vm.consumeSent()
        assertNull(vm.state.value.sentCartId)
    }

    @Test fun `a Loblaws error on send keeps the draft ready to try again`() = runTest {
        val vm = ready()
        env.on("POST", "/drafts/7/send", code = 502, body = """{"detail":"send failed: HTTPStatusError: 503"}""")
        vm.send()
        val s = vm.state.await { it.error != null }
        assertEquals("Loblaws didn't take the cart just now. Try Send again in a minute.", s.error)
        assertEquals("ready", s.draft!!.status)
        assertTrue(s.canSend)
    }

    @Test fun `offline send says so`() = runTest {
        val vm = ready()
        env.offline = true
        vm.send()
        assertEquals("Can't reach the meal-prep server. Are you on home Wi-Fi (or WireGuard)?", vm.state.await { it.error != null }.error)
        assertFalse(vm.state.value.sending)
    }

    @Test fun `edit refused because the draft changed reloads it`() = runTest {
        val vm = ready()
        env.on("PATCH", "/drafts/7/lines/31", code = 409, body = """{"detail":"draft 7 is sent, not ready"}""")
        env.on("GET", "/drafts/7", body = fixture("draft_3_sent.json").replace("\"id\": 3", "\"id\": 7").replace("\"id\":3", "\"id\":7"))
        vm.setQuantity(vm.state.value.draft!!.lines[0], 2)
        val s = vm.state.await { it.draft?.status == "sent" }
        assertEquals("This cart changed on the server — reloaded.", s.error)
        assertFalse(s.editable)
    }

    @Test fun `a failed build shows the server's reason`() = runTest {
        env.on("GET", "/drafts/7", body = """{"id":7,"status":"failed","error":"Every product search failed. Try again later.","weeks":["2026-10-11"]}""")
        val s = DraftViewModel(env.repo, 7, pollMs = 10).state.await { !it.loading }
        assertEquals("Every product search failed. Try again later.", s.error)
        assertFalse(s.canSend)
    }

    @Test fun `unknown draft stops polling`() = runTest {
        val vm = DraftViewModel(env.repo, 99, pollMs = 10)
        val s = vm.state.await { !it.loading }
        assertEquals("no route GET /drafts/99", s.error)
        Thread.sleep(100)
        assertEquals(1, env.count("GET", "/drafts/99"))
    }

    @Test fun `offline shows the reason, then Try again loads the draft`() = runTest {
        env.offline = true
        env.on("GET", "/drafts/7", body = fixture("draft_ready.json"))
        val vm = DraftViewModel(env.repo, 7, pollMs = 60_000)
        assertEquals("Can't reach the meal-prep server. Are you on home Wi-Fi (or WireGuard)?", vm.state.await { it.error != null }.error)
        env.offline = false
        vm.reload()
        assertEquals("ready", vm.state.await { it.draft != null }.draft!!.status)
        assertNull(vm.state.value.error)
    }
}
