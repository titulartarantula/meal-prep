package dev.mealprep.app.ui.loblaws

import java.net.URI

/** What the handoff clears before it loads the cart (Settings → Loblaws). */
enum class StartClear(val details: String) {
    /** "Sign out before loading the cart" off: keep everything (a signed-in start doesn't merge, see DESIGN). */
    NONE("as is (sign-out start off)"),
    /** The default: every cookie and all site data in the WebView, so PC id asks to verify the device each time. */
    ALL("signed out, everything cleared"),
    /** Experimental: only loblaws.ca's cookies and storage; accounts.pcid.ca keeps its "remember this device". */
    SITE_ONLY("signed out, PC id device trust kept (experimental)"),
}

fun startClear(signedOutStart: Boolean, keepDeviceTrust: Boolean): StartClear = when {
    !signedOutStart -> StartClear.NONE
    keepDeviceTrust -> StartClear.SITE_ONLY
    else -> StartClear.ALL
}

/**
 * Signing loblaws.ca out without touching PC id: expire each cookie the WebView holds for the site and delete the
 * site's storage. CookieManager has no "remove for one domain", so each cookie name it returns for a site URL is
 * overwritten by an already-expired cookie in every form it may have been set in: host-only, on the host, and on
 * the parent domain (".loblaws.ca"). A variant that doesn't match anything is simply ignored by the WebView.
 * Only cookies visible at "/" can be named this way; the site's sign-in lives there.
 */
object SiteReset {
    val SITE_URLS = listOf("https://www.loblaws.ca", "https://loblaws.ca")
    /** WebStorage.deleteOrigin takes origins like these. */
    val ORIGINS = SITE_URLS
    private const val EXPIRED = "Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"

    /** Names in a CookieManager.getCookie value ("a=1; b=2; a=3" → [a, b]). */
    fun cookieNames(header: String?): List<String> =
        header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

    /** Set-Cookie values that expire [names] for [url]'s host and its parent domain. */
    fun expiryCookies(url: String, names: List<String>): List<String> {
        val host = URI(url).host.lowercase()
        val parent = host.split('.').takeLast(2).joinToString(".")
        val domains = listOf(host, parent).distinct()
        return names.flatMap { n ->
            listOf("$n=; $EXPIRED; Path=/; Secure") +                       // host-only (also __Host- cookies)
                domains.map { d -> "$n=; $EXPIRED; Domain=.$d; Path=/; Secure" }
        }
    }

    /** (url, Set-Cookie) pairs for every loblaws.ca cookie; [read] = CookieManager.getCookie. */
    fun plan(read: (String) -> String?): List<Pair<String, String>> =
        SITE_URLS.flatMap { url -> expiryCookies(url, cookieNames(read(url))).map { url to it } }
}
