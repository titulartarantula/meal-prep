package dev.mealprep.app.ui.loblaws

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
    /** If the page hasn't taken the cart by then, look once more, then say so instead of spinning. */
    const val TIMEOUT_MS = 45_000L
    /** How long the last look at timeout may take before the handoff is called failed. */
    const val FINAL_CHECK_MS = 5_000L
    /** After the reload the site may still be rewriting its keys: look again this often, up to [MAX_CHECKS] looks. */
    const val RECHECK_MS = 1_500L
    const val MAX_CHECKS = 3
    /** Set on the page by [injectJs]; a reload clears it, so the check can tell the reloaded page from the old one. */
    private const val MARKER = "__mealprepCart"
    private val CART_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    /** The places the site keeps the cart id, in the order the check reports them (Details shows these names). */
    val STORES = listOf(
        "localStorage ANONYMOUS_CART_ID", "localStorage lcl-cart-id-banner",
        "sessionStorage lcl-grocery-data-cart", "cookie lcl-cart-id-banner",
    )

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
            "window.$MARKER=1;" +
            "return JSON.stringify({ok:localStorage.getItem('ANONYMOUS_CART_ID')===$id,before:b});" +
            "})()"
    }

    /**
     * After the reload: does the page still hold our cart id anywhere? Read-only. The site may rewrite a key in its own
     * format (JSON-quoted, upper case, inside a cached cart object), so each store counts if it *contains* the id.
     * Returns JSON {host, fresh, stores:[{n, f, h}]} where h is only the value's first 8 characters (for Details).
     */
    fun checkJs(cartId: String): String {
        require(isCartId(cartId)) { "not a cart id" }
        return "(function(){" +
            "var id=\"$cartId\";" +
            "function g(f){try{var v=f();return v==null?null:String(v);}catch(e){return null;}}" +
            "function s(n,v){return {n:n,f:v!=null&&v.toLowerCase().indexOf(id)>=0,h:v==null?null:v.substring(0,8)};}" +
            "var c=g(function(){var m=document.cookie.match(/(?:^|;\\s*)lcl-cart-id-banner=([^;]*)/);" +
            "return m?decodeURIComponent(m[1]):null;});" +
            "return JSON.stringify({host:location.hostname,fresh:!window.$MARKER,stores:[" +
            "s('${STORES[0]}',g(function(){return localStorage.getItem('ANONYMOUS_CART_ID');}))," +
            "s('${STORES[1]}',g(function(){return localStorage.getItem('lcl-cart-id-banner');}))," +
            "s('${STORES[2]}',g(function(){return sessionStorage.getItem('lcl-grocery-data-cart');}))," +
            "s('${STORES[3]}',c)]});" +
            "})()"
    }

    /** evaluateJavascript hands back the script's return value JSON-encoded (a quoted string here). */
    fun injectionOk(result: String?): Boolean = runCatching {
        val inner = Json.parseToJsonElement(result!!).jsonPrimitive.content
        Json.parseToJsonElement(inner).jsonObject["ok"]!!.jsonPrimitive.boolean
    }.getOrDefault(false)

    /** The [checkJs] result, or null if the script didn't run or returned something else. */
    fun parseCheck(result: String?): CartCheck? = runCatching {
        val o = Json.parseToJsonElement(Json.parseToJsonElement(result!!).jsonPrimitive.content).jsonObject
        CartCheck(
            host = o["host"]?.jsonPrimitive?.contentOrNull?.lowercase(),
            fresh = o["fresh"]!!.jsonPrimitive.boolean,
            stores = o["stores"]!!.jsonArray.map {
                val s = it.jsonObject
                StoreCheck(s["n"]!!.jsonPrimitive.content, s["f"]!!.jsonPrimitive.boolean, s["h"]?.jsonPrimitive?.contentOrNull?.let(::head))
            },
        )
    }.getOrNull()

    /** At most 8 plain characters: enough to tell our id from the site's own, never more of the site's data. */
    private fun head(v: String) = v.take(8).filter { it.isLetterOrDigit() || it in "-_{}[]\":,." }

    /** Fallback if Akamai treats the in-app browser differently from Chrome (Settings toggle). */
    fun stripWebViewMarker(ua: String): String = ua.replace("; wv)", ")").replace("Version/4.0 ", "")

    /** Host only (no path or query string). */
    fun host(url: String?): String? = runCatching { URI(url!!).host?.lowercase() }.getOrNull()

    fun isLoblaws(url: String?): Boolean = host(url).let { it == "loblaws.ca" || it?.endsWith(".loblaws.ca") == true }

    fun isSite(url: String?): Boolean = host(url) == SITE_HOST

    /** Only web pages load in the handoff browser; app links (intent:, tel:, market:…) are ignored. */
    fun isWebUrl(url: String?): Boolean = runCatching { URI(url!!).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    /** Akamai's block page is titled "Access Denied" (HTTP 403). */
    fun looksBlocked(title: String?): Boolean = title?.contains("Access Denied", ignoreCase = true) == true
}

/** One place the site keeps the cart id. [head] = the value's first 8 characters only (null = not set). */
data class StoreCheck(val name: String, val found: Boolean, val head: String?)

/** What the page held when checked. [fresh] = it is the reloaded page, not the one the id was written into. */
data class CartCheck(val host: String?, val fresh: Boolean, val stores: List<StoreCheck>) {
    /** Our id is in the site's storage on the site's own host, read after the reload. */
    val holdsCart: Boolean get() = host == LoblawsHandoff.SITE_HOST && fresh && stores.any { it.found }
}

enum class HandoffStep { LOADING, INJECTING, RELOADING, VERIFYING, READY, BLOCKED, FAILED, UNREACHABLE }

private val WORKING = setOf(HandoffStep.LOADING, HandoffStep.INJECTING, HandoffStep.RELOADING, HandoffStep.VERIFYING)

val HandoffStep.working: Boolean get() = this in WORKING

/** Closing while the cart is still going in asks first; once it's in (or it failed) Close just closes. */
fun closeNeedsConfirm(step: HandoffStep): Boolean = step.working

sealed interface HandoffAction {
    data class Run(val js: String) : HandoffAction
    /** Run [js] after [delayMs] (a re-check while the site settles). */
    data class RunLater(val delayMs: Long, val js: String) : HandoffAction
    data object Reload : HandoffAction
    data object None : HandoffAction
}

/**
 * Load loblaws.ca → set the cart id → reload → check it stuck → ready (then the shopper signs in).
 * The check is lenient about *where* the reloaded page keeps the id (any of the four stores) and patient (a few
 * re-checks, one more look at timeout), but never claims success it didn't see: success needs the id read back on
 * www.loblaws.ca from the reloaded page. A block page, a script that didn't take, a page without the id, a load
 * error or a timeout all end in a state with Try again.
 */
class HandoffMachine(private val cartId: String) {
    init { require(LoblawsHandoff.isCartId(cartId)) { "not a cart id" } }

    var step = HandoffStep.LOADING
        private set
    /** The step it was in when it stopped short of ready (for Details). */
    var stoppedAt: HandoffStep? = null
        private set
    /** The last thing the check script reported (for Details). */
    var lastCheck: CartCheck? = null
        private set
    /** Checks asked for in this attempt. */
    var checks = 0
        private set
    private var finalCheck = false

    val working: Boolean get() = step.working

    fun onPageFinished(url: String?, title: String?): HandoffAction {
        // A block page is reported whenever it shows, even on her sign-in pages after the cart went in.
        if (LoblawsHandoff.looksBlocked(title)) { stop(HandoffStep.BLOCKED); return HandoffAction.None }
        if (!working) return HandoffAction.None                     // ready (her sign-in pages) or already failed
        if (!LoblawsHandoff.isSite(url)) return HandoffAction.None   // a redirect on the way: wait for the site
        return when (step) {
            HandoffStep.LOADING -> { step = HandoffStep.INJECTING; HandoffAction.Run(LoblawsHandoff.injectJs(cartId)) }
            HandoffStep.RELOADING -> { step = HandoffStep.VERIFYING; checks++; HandoffAction.Run(LoblawsHandoff.checkJs(cartId)) }
            else -> HandoffAction.None
        }
    }

    /** Result of a script [onPageFinished], [onTimeout] or a [HandoffAction.RunLater] asked to run. */
    fun onScriptResult(result: String?): HandoffAction = when (step) {
        HandoffStep.INJECTING ->
            if (LoblawsHandoff.injectionOk(result)) { step = HandoffStep.RELOADING; HandoffAction.Reload } else fail()
        HandoffStep.VERIFYING -> verify(LoblawsHandoff.parseCheck(result))
        else -> HandoffAction.None
    }

    private fun verify(c: CartCheck?): HandoffAction {
        if (c != null) lastCheck = c
        return when {
            c?.holdsCart == true -> { step = HandoffStep.READY; HandoffAction.None }
            finalCheck -> fail()
            c != null && !c.fresh -> { step = HandoffStep.RELOADING; HandoffAction.None }   // the old page answered: wait for the reload
            checks < LoblawsHandoff.MAX_CHECKS -> { checks++; HandoffAction.RunLater(LoblawsHandoff.RECHECK_MS, LoblawsHandoff.checkJs(cartId)) }
            else -> fail()
        }
    }

    /** The main page failed to load (no connection, DNS…). */
    fun onLoadError() { if (working) stop(HandoffStep.UNREACHABLE) }

    /**
     * First call ([LoblawsHandoff.TIMEOUT_MS]): once the id was written and a reload asked for, look once more (the
     * cart may be in even if the page never said so); before that, fail. Second call (the last look didn't answer
     * within [LoblawsHandoff.FINAL_CHECK_MS]): fail.
     */
    fun onTimeout(): HandoffAction {
        if (!working) return HandoffAction.None
        if (finalCheck || step == HandoffStep.LOADING || step == HandoffStep.INJECTING) return fail()
        finalCheck = true; step = HandoffStep.VERIFYING; checks++
        return HandoffAction.Run(LoblawsHandoff.checkJs(cartId))
    }

    fun restart() { step = HandoffStep.LOADING; stoppedAt = null; lastCheck = null; checks = 0; finalCheck = false }

    private fun stop(to: HandoffStep) { if (step != to) stoppedAt = step; step = to }

    private fun fail(): HandoffAction { stop(HandoffStep.FAILED); return HandoffAction.None }
}

/**
 * The "Details" text shown (and copied) when the handoff stops short: the step, the page's host and, for each store,
 * whether it held the cart id plus the value's first 8 characters. No paths, query strings, cookies or other site data.
 */
fun handoffDetails(step: HandoffStep, stoppedAt: HandoffStep?, pageHost: String?, check: CartCheck?, checks: Int): String =
    buildString {
        append("Step: ").append(step.name)
        if (stoppedAt != null) append(" (stopped while ").append(stoppedAt.name).append(")")
        append("\nPage: ").append(pageHost ?: "none")
        append("\nChecks: ").append(checks)
        if (check == null) { append("\nNo storage check ran."); return@buildString }
        append("\nChecked on: ").append(check.host ?: "unknown").append(if (check.fresh) " (reloaded page)" else " (before the reload)")
        for (name in LoblawsHandoff.STORES) {
            val s = check.stores.firstOrNull { it.name == name }
            append("\n").append(name).append(": ")
            when {
                s == null -> append("not read")
                s.head == null -> append("no (not set)")
                else -> append(if (s.found) "yes" else "no").append(" \"").append(s.head).append("…\"")
            }
        }
    }
