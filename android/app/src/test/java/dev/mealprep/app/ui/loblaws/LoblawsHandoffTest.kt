package dev.mealprep.app.ui.loblaws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoblawsHandoffTest {
    private val id = "24b34302-2508-4795-ab5d-2f2f9a7de03c"
    private val ok = "\"{\\\"ok\\\":true,\\\"before\\\":{\\\"a\\\":null,\\\"c\\\":null}}\""   // evaluateJavascript's JSON-encoded string
    private val notOk = "\"{\\\"ok\\\":false}\""

    @Test fun `script sets both storage keys, clears the cached cart and reports back`() {
        val js = LoblawsHandoff.injectJs(id)
        assertTrue(js.contains("""localStorage.setItem('ANONYMOUS_CART_ID',"$id")"""))
        assertTrue(js.contains("""localStorage.setItem('lcl-cart-id-banner',"$id")"""))
        assertTrue(js.contains("sessionStorage.removeItem('lcl-grocery-data-cart')"))
        assertTrue(js.contains("JSON.stringify"))
        assertEquals("lcl-cart-id-banner=$id; Domain=.loblaws.ca; Path=/; Secure", LoblawsHandoff.cookie(id))
    }

    @Test fun `check script only reads`() {
        val js = LoblawsHandoff.checkJs(id)
        assertTrue(js.contains("""localStorage.getItem('ANONYMOUS_CART_ID')==="$id""""))
        assertFalse(js.contains("setItem"))
        assertFalse(js.contains("removeItem"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cart id must be a uuid`() { LoblawsHandoff.injectJs("x\"); alert(1); //") }

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
    }

    @Test fun `happy path loads, injects once, reloads, checks, then is ready`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.Run(LoblawsHandoff.injectJs(id)), m.onPageFinished("https://www.loblaws.ca/", "Loblaws"))
        assertEquals(HandoffStep.INJECTING, m.step)
        assertEquals(HandoffAction.None, m.onPageFinished("https://www.loblaws.ca/", "Loblaws"))   // a second finish: no re-inject
        assertEquals(HandoffAction.Reload, m.onScriptResult(ok))
        assertEquals(HandoffStep.RELOADING, m.step)
        assertEquals(HandoffAction.Run(LoblawsHandoff.checkJs(id)), m.onPageFinished("https://www.loblaws.ca/", "Loblaws"))
        assertEquals(HandoffAction.None, m.onScriptResult("\"{\\\"ok\\\":true}\""))
        assertEquals(HandoffStep.READY, m.step)
        assertEquals(HandoffAction.None, m.onPageFinished("https://accounts.pcid.ca/login", "PC id"))   // her sign-in: no re-inject
        assertEquals(HandoffAction.None, m.onPageFinished("https://www.loblaws.ca/", "Loblaws"))       // back after sign-in
        assertEquals(HandoffStep.READY, m.step)
    }

    @Test fun `access denied page is reported as blocked`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.None, m.onPageFinished("https://www.loblaws.ca/", "Access Denied"))
        assertEquals(HandoffStep.BLOCKED, m.step)
        assertEquals(HandoffAction.None, m.onScriptResult(ok))      // a late script result can't turn it into success
        assertEquals(HandoffStep.BLOCKED, m.step)
        m.restart()
        assertEquals(HandoffStep.LOADING, m.step)
    }

    @Test fun `a block page after the cart went in is still reported`() {
        val m = ready()
        m.onPageFinished("https://accounts.pcid.ca/login", "Access Denied")
        assertEquals(HandoffStep.BLOCKED, m.step)
    }

    @Test fun `failed injection is reported`() {
        val m = HandoffMachine(id)
        m.onPageFinished("https://www.loblaws.ca/", "Loblaws")
        assertEquals(HandoffAction.None, m.onScriptResult("null"))
        assertEquals(HandoffStep.FAILED, m.step)
    }

    @Test fun `a page that dropped the cart id after the reload is reported`() {
        val m = HandoffMachine(id)
        m.onPageFinished("https://www.loblaws.ca/", "Loblaws")
        m.onScriptResult(ok)
        m.onPageFinished("https://www.loblaws.ca/", "Loblaws")
        assertEquals(HandoffAction.None, m.onScriptResult(notOk))
        assertEquals(HandoffStep.FAILED, m.step)
    }

    @Test fun `a redirect off the site is not injected`() {
        val m = HandoffMachine(id)
        assertEquals(HandoffAction.None, m.onPageFinished("https://example.com/", "x"))
        assertEquals(HandoffAction.None, m.onPageFinished("https://loblaws.ca/", "Loblaws"))   // bare host: other storage
        assertEquals(HandoffStep.LOADING, m.step)
    }

    @Test fun `no connection and timeouts end the spinner`() {
        val m = HandoffMachine(id)
        m.onLoadError()
        assertEquals(HandoffStep.UNREACHABLE, m.step)
        m.restart()
        m.onPageFinished("https://www.loblaws.ca/", "Loblaws")
        m.onTimeout()
        assertEquals(HandoffStep.FAILED, m.step)
        val r = ready()
        r.onTimeout(); r.onLoadError()                               // after success these change nothing
        assertEquals(HandoffStep.READY, r.step)
    }

    private fun ready() = HandoffMachine(id).apply {
        onPageFinished("https://www.loblaws.ca/", "Loblaws"); onScriptResult(ok)
        onPageFinished("https://www.loblaws.ca/", "Loblaws"); onScriptResult(ok)
        assertEquals(HandoffStep.READY, step)
    }
}
