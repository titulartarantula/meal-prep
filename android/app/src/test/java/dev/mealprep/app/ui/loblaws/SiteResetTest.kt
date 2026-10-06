package dev.mealprep.app.ui.loblaws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteResetTest {
    private val exp = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

    @Test fun `always a signed-out start, the switch says how much is cleared`() {
        assertEquals(StartClear.SITE_ONLY, startClear(keepDeviceTrust = true))      // the default
        assertEquals(StartClear.ALL, startClear(keepDeviceTrust = false))
        assertEquals(listOf(StartClear.SITE_ONLY, StartClear.ALL), StartClear.entries)   // no "as is" start any more
    }

    @Test fun `cookie names come from the CookieManager header`() {
        assertEquals(listOf("a", "lcl-cart-id-banner", "__Host-s"), SiteReset.cookieNames("a=1; lcl-cart-id-banner=x=y; __Host-s=2; a=3"))
        assertEquals(emptyList<String>(), SiteReset.cookieNames(null))
        assertEquals(emptyList<String>(), SiteReset.cookieNames(" ; "))
    }

    @Test fun `each cookie is expired host-only, on the host and on the parent domain`() {
        assertEquals(listOf("s=; $exp; Path=/; Secure", "s=; $exp; Domain=.www.loblaws.ca; Path=/; Secure",
            "s=; $exp; Domain=.loblaws.ca; Path=/; Secure"), SiteReset.expiryCookies("https://www.loblaws.ca", listOf("s")))
        assertEquals(listOf("s=; $exp; Path=/; Secure", "s=; $exp; Domain=.loblaws.ca; Path=/; Secure"),
            SiteReset.expiryCookies("https://loblaws.ca", listOf("s")))
    }

    @Test fun `only loblaws ca is read and expired, never PC id`() {
        val asked = mutableListOf<String>()
        val plan = SiteReset.plan { url -> asked += url; if (url == "https://www.loblaws.ca") "session=1; cart=2" else null }
        assertEquals(listOf("https://www.loblaws.ca", "https://loblaws.ca"), asked)
        assertEquals(6, plan.size)                                            // 2 cookies × 3 forms
        assertTrue(plan.all { (url, c) -> url == "https://www.loblaws.ca" && c.contains("Max-Age=0") })
        assertFalse(plan.any { (url, c) -> "pcid" in url || "pcid" in c })
        assertEquals(listOf("https://www.loblaws.ca", "https://loblaws.ca"), SiteReset.ORIGINS)
    }

    @Test fun `details say which start was used`() {
        val d = handoffDetails(HandoffStep.FAILED, HandoffStep.VERIFYING, "www.loblaws.ca", null, 3, StartClear.SITE_ONLY)
        assertTrue(d, d.contains("\nStart: signed out, PC id device trust kept\n"))
        assertTrue(handoffDetails(HandoffStep.FAILED, null, null, null, 0, StartClear.ALL)
            .contains("Start: signed out, everything cleared (device trust off)"))
        assertFalse(handoffDetails(HandoffStep.FAILED, null, null, null, 0).contains("Start:"))
    }
}
