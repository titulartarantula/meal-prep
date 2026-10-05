package dev.mealprep.app.ui.loblaws

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Confirmed 2026-10-04 (DESIGN "Spike results", option a): loblaws.ca keeps the anonymous cart in localStorage
 * ANONYMOUS_CART_ID + lcl-cart-id-banner (+ cookie lcl-cart-id-banner) and caches it in sessionStorage
 * lcl-grocery-data-cart. Setting them to the server-built cart id and reloading shows the cart; signing in with
 * PC id merges it into the account cart. The app never signs in, never sees a password and never checks out.
 */
object LoblawsHandoff {
    const val START_URL = "https://www.loblaws.ca/"
    const val COOKIE_URL = "https://www.loblaws.ca"
    /** localStorage is per origin: the keys only count on the site's own host. */
    const val SITE_HOST = "www.loblaws.ca"
    /** If the page hasn't taken the cart by then, say so instead of spinning. */
    const val TIMEOUT_MS = 45_000L
    private val CART_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun isCartId(s: String) = CART_ID.matches(s)

    fun cookie(cartId: String): String {
        require(isCartId(cartId)) { "not a cart id" }
        return "lcl-cart-id-banner=$cartId; Domain=.loblaws.ca; Path=/; Secure"
    }

    /** Returns JSON {ok, before} as a string; `before` (the page's previous values) is logged to help debug merges. */
    fun injectJs(cartId: String): String {
        require(isCartId(cartId)) { "not a cart id" }   // the id goes into a script: only a UUID is allowed
        val id = "\"$cartId\""
        return "(function(){" +
            "var b={a:localStorage.getItem('ANONYMOUS_CART_ID'),c:localStorage.getItem('lcl-cart-id-banner')};" +
            "localStorage.setItem('ANONYMOUS_CART_ID',$id);" +
            "localStorage.setItem('lcl-cart-id-banner',$id);" +
            "sessionStorage.removeItem('lcl-grocery-data-cart');" +
            "return JSON.stringify({ok:localStorage.getItem('ANONYMOUS_CART_ID')===$id,before:b});" +
            "})()"
    }

    /** After the reload: did the page keep our cart id (rather than start a fresh cart of its own)? Read-only. */
    fun checkJs(cartId: String): String {
        require(isCartId(cartId)) { "not a cart id" }
        val id = "\"$cartId\""
        return "(function(){" +
            "return JSON.stringify({ok:localStorage.getItem('ANONYMOUS_CART_ID')===$id&&localStorage.getItem('lcl-cart-id-banner')===$id});" +
            "})()"
    }

    /** evaluateJavascript hands back the script's return value JSON-encoded (a quoted string here). */
    fun injectionOk(result: String?): Boolean = runCatching {
        val inner = Json.parseToJsonElement(result!!).jsonPrimitive.content
        Json.parseToJsonElement(inner).jsonObject["ok"]!!.jsonPrimitive.boolean
    }.getOrDefault(false)

    /** Fallback if Akamai treats the in-app browser differently from Chrome (Settings toggle). */
    fun stripWebViewMarker(ua: String): String = ua.replace("; wv)", ")").replace("Version/4.0 ", "")

    private fun host(url: String?): String? = runCatching { URI(url!!).host?.lowercase() }.getOrNull()

    fun isLoblaws(url: String?): Boolean = host(url).let { it == "loblaws.ca" || it?.endsWith(".loblaws.ca") == true }

    fun isSite(url: String?): Boolean = host(url) == SITE_HOST

    /** Only web pages load in the handoff browser; app links (intent:, tel:, market:…) are ignored. */
    fun isWebUrl(url: String?): Boolean = runCatching { URI(url!!).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    /** Akamai's block page is titled "Access Denied" (HTTP 403). */
    fun looksBlocked(title: String?): Boolean = title?.contains("Access Denied", ignoreCase = true) == true
}

enum class HandoffStep { LOADING, INJECTING, RELOADING, VERIFYING, READY, BLOCKED, FAILED, UNREACHABLE }

sealed interface HandoffAction {
    data class Run(val js: String) : HandoffAction
    data object Reload : HandoffAction
    data object None : HandoffAction
}

/**
 * Load loblaws.ca → set the cart id → reload → check it stuck → ready (then the shopper signs in).
 * Never claims success it didn't see: a block page, a script that didn't take, a page that dropped the id, a load
 * error or a timeout all end in a state with Try again.
 */
class HandoffMachine(private val cartId: String) {
    init { require(LoblawsHandoff.isCartId(cartId)) { "not a cart id" } }

    var step = HandoffStep.LOADING
        private set

    val working: Boolean get() = step in WORKING

    fun onPageFinished(url: String?, title: String?): HandoffAction {
        // A block page is reported whenever it shows, even on her sign-in pages after the cart went in.
        if (LoblawsHandoff.looksBlocked(title)) { step = HandoffStep.BLOCKED; return HandoffAction.None }
        if (!working) return HandoffAction.None                     // ready (her sign-in pages) or already failed
        if (!LoblawsHandoff.isSite(url)) return HandoffAction.None   // a redirect on the way: wait for the site
        return when (step) {
            HandoffStep.LOADING -> { step = HandoffStep.INJECTING; HandoffAction.Run(LoblawsHandoff.injectJs(cartId)) }
            HandoffStep.RELOADING -> { step = HandoffStep.VERIFYING; HandoffAction.Run(LoblawsHandoff.checkJs(cartId)) }
            else -> HandoffAction.None
        }
    }

    /** Result of the script [onPageFinished] asked to run. */
    fun onScriptResult(result: String?): HandoffAction {
        val ok = LoblawsHandoff.injectionOk(result)
        return when (step) {
            HandoffStep.INJECTING -> if (ok) { step = HandoffStep.RELOADING; HandoffAction.Reload } else fail()
            HandoffStep.VERIFYING -> if (ok) { step = HandoffStep.READY; HandoffAction.None } else fail()
            else -> HandoffAction.None
        }
    }

    /** The main page failed to load (no connection, DNS…). */
    fun onLoadError() { if (working) step = HandoffStep.UNREACHABLE }

    fun onTimeout() { if (working) step = HandoffStep.FAILED }

    fun restart() { step = HandoffStep.LOADING }

    private fun fail(): HandoffAction { step = HandoffStep.FAILED; return HandoffAction.None }

    private companion object {
        val WORKING = setOf(HandoffStep.LOADING, HandoffStep.INJECTING, HandoffStep.RELOADING, HandoffStep.VERIFYING)
    }
}
