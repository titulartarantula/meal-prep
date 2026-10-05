package dev.mealprep.app.ui.loblaws

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoblawsHandoffTest {
    private val id = "24b34302-2508-4795-ab5d-2f2f9a7de03c"
    private val ok = "\"{\\\"ok\\\":true,\\\"before\\\":{\\\"a\\\":null,\\\"c\\\":null}}\""   // evaluateJavascript's JSON-encoded string
    private val notOk = "\"{\\\"ok\\\":false}\""
    private val site = "https://www.loblaws.ca/"

    /** What checkJs returns for these store values (same rule as the script: a store counts if it contains the id). */
    private fun check(
        anon: String? = null, banner: String? = null, session: String? = null, cookie: String? = null,
        host: String = "www.loblaws.ca", fresh: Boolean = true,
    ): String {
        val inner = buildJsonObject {
            put("host", host); put("fresh", fresh)
            put("stores", buildJsonArray {
                listOf(anon, banner, session, cookie).forEachIndexed { i, v ->
                    addJsonObject {
                        put("n", LoblawsHandoff.STORES[i])
                        put("f", v?.lowercase()?.contains(id) == true)
                        put("h", v?.take(8))
                    }
                }
            })
        }.toString()
        return JsonPrimitive(inner).toString()
    }
    private val other = "11111111-2222-4333-8444-555555555555"
    private val empty get() = check(anon = other, banner = other)

    @Test fun `script sets both storage keys, clears the cached cart, marks the page and reports back`() {
        val js = LoblawsHandoff.injectJs(id)
        assertTrue(js.contains("""localStorage.setItem('ANONYMOUS_CART_ID',"$id")"""))
        assertTrue(js.contains("""localStorage.setItem('lcl-cart-id-banner',"$id")"""))
        assertTrue(js.contains("sessionStorage.removeItem('lcl-grocery-data-cart')"))
        assertTrue(js.contains("window.__mealprepCart=1"))
        assertTrue(js.contains("JSON.stringify"))
        assertEquals("lcl-cart-id-banner=$id; Domain=.loblaws.ca; Path=/; Secure", LoblawsHandoff.cookie(id))
    }

    @Test fun `check script only reads, looks in all four stores and matches leniently`() {
        val js = LoblawsHandoff.checkJs(id)
        assertTrue(js.contains("localStorage.getItem('ANONYMOUS_CART_ID')"))
        assertTrue(js.contains("localStorage.getItem('lcl-cart-id-banner')"))
        assertTrue(js.contains("sessionStorage.getItem('lcl-grocery-data-cart')"))
        assertTrue(js.contains("document.cookie"))
        assertTrue(js.contains("toLowerCase().indexOf(id)>=0"))
        assertTrue(js.contains("substring(0,8)"))
        assertTrue(js.contains("fresh:!window.__mealprepCart"))
        assertFalse(js.contains("setItem"))
        assertFalse(js.contains("removeItem"))
        assertFalse(js.contains("document.cookie="))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cart id must be a uuid`() { LoblawsHandoff.injectJs("x\"); alert(1); //") }

    @Test(expected = IllegalArgumentException::class)
    fun `check script refuses a bad id too`() { LoblawsHandoff.checkJs("x\"; alert(1); //") }

    @Test fun `only lowercase uuids are cart ids`() {
        assertTrue(LoblawsHandoff.isCartId(id))
        assertFalse(LoblawsHandoff.isCartId(id.uppercase()))
        assertFalse(LoblawsHandoff.isCartId("$id\n"))
        assertFalse(LoblawsHandoff.isCartId(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the machine refuses a bad cart id too`() { HandoffMachine("'; drop") }

    @Test fun `injection result parsing`() {
        assertTrue(LoblawsHandoff.injectionOk(ok))
        assertFalse(LoblawsHandoff.injectionOk("null"))
        assertFalse(LoblawsHandoff.injectionOk(null))
        assertFalse(LoblawsHandoff.injectionOk(""))
        assertFalse(LoblawsHandoff.injectionOk(notOk))
        assertFalse(LoblawsHandoff.injectionOk("\"not json\""))
    }

    @Test fun `check result parsing keeps only 8 characters of each value`() {
        val c = LoblawsHandoff.parseCheck(check(anon = "\"$id\"", session = """{"cart":{"id":"$id","entries":[]}}"""))!!
        assertEquals("www.loblaws.ca", c.host)
        assertTrue(c.fresh)
        assertEquals(LoblawsHandoff.STORES, c.stores.map { it.name })
        assertEquals(listOf(true, false, true, false), c.stores.map { it.found })
        assertEquals(listOf("\"24b3430", null, "{\"cart\":", null), c.stores.map { it.head })
        assertTrue(c.holdsCart)
        assertNull(LoblawsHandoff.parseCheck(null))
        assertNull(LoblawsHandoff.parseCheck("null"))
        assertNull(LoblawsHandoff.parseCheck(ok))            // an injection result is not a check
        assertNull(LoblawsHandoff.parseCheck("\"not json\""))
    }

    @Test fun `webview marker can be hidden`() {
        val ua = "Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP2A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.0.0 Mobile Safari/537.36"
        assertEquals("Mozilla/5.0 (Linux; Android 16; Pixel 9 Build/BP2A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36",
            LoblawsHandoff.stripWebViewMarker(ua))
    }

    @Test fun `urls`() {
        assertTrue(LoblawsHandoff.isSite("https://www.loblaws.ca/cart"))
        assertFalse(LoblawsHandoff.isSite("https://www.loblaws.ca.evil.com/"))
        assertTrue(LoblawsHandoff.isLoblaws("https://accounts.loblaws.ca/x"))
        assertFalse(LoblawsHandoff.isLoblaws("https://notloblaws.ca/"))
        assertFalse(LoblawsHandoff.isLoblaws(null))
        assertTrue(LoblawsHandoff.isWebUrl("https://accounts.pcid.ca/login"))
        assertFalse(LoblawsHandoff.isWebUrl("intent://cart#Intent;scheme=pcexpress;end"))
        assertFalse(LoblawsHandoff.isWebUrl("tel:18005555555"))
        assertEquals("www.loblaws.ca", LoblawsHandoff.host("https://www.loblaws.ca/en/cart?token=abc#x"))
    }

    @Test fun `happy path loads, injects once, reloads, checks, then is ready`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.Run(LoblawsHandoff.injectJs(id)), m.onPageFinished(site, "Loblaws"))
        assertEquals(HandoffStep.INJECTING, m.step)
        assertEquals(HandoffAction.None, m.onPageFinished(site, "Loblaws"))   // a second finish: no re-inject
        assertEquals(HandoffAction.Reload, m.onScriptResult(ok))
        assertEquals(HandoffStep.RELOADING, m.step)
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), m.onPageFinished(site, "Loblaws"))
        assertEquals(HandoffAction.None, m.onScriptResult(check(anon = id, banner = id)))
        assertEquals(HandoffStep.READY, m.step)
        assertEquals(HandoffAction.None, m.onPageFinished("https://accounts.pcid.ca/login", "PC id"))   // her sign-in: no re-inject
        assertEquals(HandoffAction.None, m.onPageFinished(site, "Loblaws"))       // back after sign-in
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `any one store holding the id is enough`() {
        val sources = mapOf(
            "ANONYMOUS_CART_ID rewritten JSON-quoted" to check(anon = "\"$id\"", banner = other),
            "lcl-cart-id-banner in upper case" to check(banner = id.uppercase()),
            "only the cached cart" to check(anon = other, session = """{"cartId":"$id","entries":19}"""),
            "only the cookie" to check(cookie = id),
        )
        for ((what, result) in sources) {
            val m = reloaded()
            assertEquals(what, HandoffAction.None, m.onScriptResult(result))
            assertEquals(what, HandoffStep.READY, m.step)
        }
    }

    @Test fun `a page without the id is checked again a couple of times before failing`() {
        val m = reloaded()
        val again = HandoffAction.RunLater(LoblawsHandoff.RECHECK_MS, LoblawsHandoff.checkJs(id))
        assertEquals(again, m.onScriptResult(empty))
        assertEquals(HandoffStep.VERIFYING, m.step)
        assertEquals(again, m.onScriptResult("null"))           // a script that didn't answer counts as not yet
        assertEquals(HandoffAction.None, m.onScriptResult(empty))
        assertEquals(HandoffStep.FAILED, m.step)
        assertEquals(HandoffStep.VERIFYING, m.stoppedAt)
        assertEquals(LoblawsHandoff.MAX_CHECKS, m.checks)
    }

    @Test fun `a re-check that finds the id succeeds`() {
        val m = reloaded()
        m.onScriptResult(empty)
        m.onScriptResult(check(session = "{\"id\":\"$id\"}"))
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `the id read on another host does not count`() {
        val m = reloaded()
        m.onScriptResult(check(cookie = id, host = "loblaws.ca"))   // shared cookie domain, but not the site's storage
        assertEquals(HandoffStep.VERIFYING, m.step)
        m.onScriptResult(check(anon = id, host = "accounts.pcid.ca"))
        m.onScriptResult(check(anon = id, host = "www.loblaws.ca.evil.com"))
        assertEquals(HandoffStep.FAILED, m.step)
    }

    @Test fun `the page the id was written into does not count as the reloaded page`() {
        val m = reloaded()
        assertEquals(HandoffAction.None, m.onScriptResult(check(anon = id, banner = id, fresh = false)))
        assertEquals(HandoffStep.RELOADING, m.step)                      // wait for the real reload
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), m.onPageFinished(site, "Loblaws"))
        m.onScriptResult(check(anon = id))
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `redirects on the way back are ignored`() {
        val m = HandoffMachine(id)
        m.onPageFinished(site, "Loblaws"); m.onScriptResult(ok)
        assertEquals(HandoffAction.None, m.onPageFinished("https://loblaws.ca/", "Loblaws"))
        assertEquals(HandoffAction.None, m.onPageFinished("https://accounts.pcid.ca/redirect", "PC id"))
        assertEquals(HandoffAction.None, m.onPageFinished("about:blank", null))
        assertEquals(HandoffStep.RELOADING, m.step)
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), m.onPageFinished("https://www.loblaws.ca/en?x=1", "Loblaws"))
        m.onScriptResult(check(banner = id))
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `a redirect off the site is not injected`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.None, m.onPageFinished("https://example.com/", "x"))
        assertEquals(HandoffAction.None, m.onPageFinished("https://loblaws.ca/", "Loblaws"))   // bare host: other storage
        assertEquals(HandoffStep.LOADING, m.step)
    }

    @Test fun `timeout with the cart in is ready`() {
        val m = HandoffMachine(id)
        m.onPageFinished(site, "Loblaws"); m.onScriptResult(ok)          // reload asked for, but the page never said it finished
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), m.onTimeout())
        assertEquals(HandoffStep.VERIFYING, m.step)
        m.onScriptResult(check(anon = id))
        assertEquals(HandoffStep.READY, m.step)
        assertEquals(HandoffAction.None, m.onTimeout())                  // the second timer changes nothing
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `timeout while re-checking looks once more and can still succeed`() {
        val m = reloaded()
        m.onScriptResult(empty)
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), m.onTimeout())
        m.onScriptResult(check(cookie = id))
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `timeout without the cart fails, with no further re-checks`() {
        val m = reloaded()
        m.onTimeout()
        assertEquals(HandoffAction.None, m.onScriptResult(empty))
        assertEquals(HandoffStep.FAILED, m.step)
        assertEquals(HandoffAction.None, m.onScriptResult(check(anon = id)))   // a late answer can't turn it into success
        assertEquals(HandoffStep.FAILED, m.step)
    }

    @Test fun `timeout whose last look never answers fails`() {
        val m = reloaded()
        m.onTimeout()
        assertEquals(HandoffAction.None, m.onTimeout())
        assertEquals(HandoffStep.FAILED, m.step)
    }

    @Test fun `timeout before the id was written fails straight away`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.None, m.onTimeout())
        assertEquals(HandoffStep.FAILED, m.step)
        assertEquals(HandoffStep.LOADING, m.stoppedAt)
        m.restart()
        m.onPageFinished(site, "Loblaws")
        m.onTimeout()
        assertEquals(HandoffStep.FAILED, m.step)
        assertEquals(HandoffStep.INJECTING, m.stoppedAt)
    }

    @Test fun `access denied page is reported as blocked`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.None, m.onPageFinished(site, "Access Denied"))
        assertEquals(HandoffStep.BLOCKED, m.step)
        assertEquals(HandoffAction.None, m.onScriptResult(ok))      // a late script result can't turn it into success
        assertEquals(HandoffStep.BLOCKED, m.step)
        m.restart()
        assertEquals(HandoffStep.LOADING, m.step)
        assertNull(m.stoppedAt)
    }

    @Test fun `a block page after the cart went in is still reported`() {
        val m = ready()
        m.onPageFinished("https://accounts.pcid.ca/login", "Access Denied")
        assertEquals(HandoffStep.BLOCKED, m.step)
    }

    @Test fun `failed injection is reported`() {
        val m = HandoffMachine(id)
        m.onPageFinished(site, "Loblaws")
        assertEquals(HandoffAction.None, m.onScriptResult("null"))
        assertEquals(HandoffStep.FAILED, m.step)
    }

    @Test fun `no connection ends the spinner, and nothing undoes ready`() {
        val m = HandoffMachine(id)
        m.onLoadError()
        assertEquals(HandoffStep.UNREACHABLE, m.step)
        val r = ready()
        r.onTimeout(); r.onLoadError()
        assertEquals(HandoffStep.READY, r.step)
    }

    @Test fun `restart forgets the last attempt`() {
        val m = reloaded()
        m.onScriptResult(empty); m.onTimeout(); m.onScriptResult(empty)
        assertEquals(HandoffStep.FAILED, m.step)
        m.restart()
        assertEquals(0, m.checks)
        assertNull(m.lastCheck)
        assertEquals(HandoffAction.Run(LoblawsHandoff.injectJs(id)), m.onPageFinished(site, "Loblaws"))
    }

    @Test fun `close asks first only while the cart is still going in`() {
        listOf(HandoffStep.LOADING, HandoffStep.INJECTING, HandoffStep.RELOADING, HandoffStep.VERIFYING)
            .forEach { assertTrue(it.name, closeNeedsConfirm(it)) }
        listOf(HandoffStep.READY, HandoffStep.BLOCKED, HandoffStep.FAILED, HandoffStep.UNREACHABLE)
            .forEach { assertFalse(it.name, closeNeedsConfirm(it)) }
    }

    @Test fun `details say where the page held the id, with 8 characters at most`() {
        val m = reloaded()
        val session = """{"cart":{"id":"abc","customer":"someone@example.com"}}"""
        m.onScriptResult(check(anon = other, session = session))
        m.onScriptResult(check(anon = other, session = session))
        m.onScriptResult(check(anon = other, session = session))
        val d = handoffDetails(m.step, m.stoppedAt, "www.loblaws.ca", m.lastCheck, m.checks)
        assertTrue(d, d.contains("Step: FAILED (stopped while VERIFYING)"))
        assertTrue(d, d.contains("Page: www.loblaws.ca"))
        assertTrue(d, d.contains("Checks: 3"))
        assertTrue(d, d.contains("localStorage ANONYMOUS_CART_ID: no \"11111111…\""))
        assertTrue(d, d.contains("localStorage lcl-cart-id-banner: no (not set)"))
        assertTrue(d, d.contains("sessionStorage lcl-grocery-data-cart: no \"{\"cart\":…\""))
        assertTrue(d, d.contains("cookie lcl-cart-id-banner: no (not set)"))
        assertFalse(d, d.contains("someone"))
        assertFalse(d, d.contains(other))
        assertFalse(d, d.contains(id))
    }

    @Test fun `details before any check`() {
        val d = handoffDetails(HandoffStep.UNREACHABLE, HandoffStep.LOADING, null, null, 0)
        assertEquals("Step: UNREACHABLE (stopped while LOADING)\nPage: none\nChecks: 0\nNo storage check ran.", d)
    }

    /** Injected and reloaded: the first check is out. */
    private fun reloaded() = HandoffMachine(id).apply {
        onPageFinished(site, "Loblaws"); onScriptResult(ok)
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), onPageFinished(site, "Loblaws"))
        assertEquals(HandoffStep.VERIFYING, step)
    }

    private fun ready() = reloaded().apply {
        onScriptResult(check(anon = id, banner = id))
        assertEquals(HandoffStep.READY, step)
    }
}
