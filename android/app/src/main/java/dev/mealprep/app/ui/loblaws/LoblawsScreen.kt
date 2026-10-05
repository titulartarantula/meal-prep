package dev.mealprep.app.ui.loblaws

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.data.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the banner above the Loblaws page says in each step. */
fun bannerText(step: HandoffStep): String = when (step) {
    HandoffStep.LOADING, HandoffStep.INJECTING, HandoffStep.RELOADING, HandoffStep.VERIFYING -> "Putting your cart into Loblaws…"
    HandoffStep.READY -> "Your cart is in. Tap Sign in and sign in with your PC id — the items move into your account. " +
        "Then pick a pickup time and check out as usual."
    HandoffStep.BLOCKED -> "Loblaws blocked the in-app browser. Try again, or turn on Settings → Hide in-app browser marker."
    HandoffStep.FAILED -> "The Loblaws page didn't take the cart. Try again."
    HandoffStep.UNREACHABLE -> "Couldn't open loblaws.ca. Check the phone's connection, then try again."
}

/** The line beside Close: what closing leaves behind. */
fun closeHint(step: HandoffStep): String? = when (step) {
    HandoffStep.READY -> "Your cart stays in Loblaws."
    HandoffStep.BLOCKED, HandoffStep.FAILED, HandoffStep.UNREACHABLE -> "You can open the cart again from the week."
    else -> null
}

const val CLOSE_CONFIRM = "Still loading your cart. Close anyway?"

private val STOPPED = setOf(HandoffStep.BLOCKED, HandoffStep.FAILED, HandoffStep.UNREACHABLE)

@Composable
fun HandoffBanner(
    step: HandoffStep,
    details: String?,
    onRetry: () -> Unit,
    onCopy: () -> Unit,
    onCopyDetails: () -> Unit,
    onClose: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClose) { Text("Close") }
            closeHint(step)?.let { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
        }
        Text(bannerText(step), style = MaterialTheme.typography.bodyMedium)
        if (step.working) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (step in STOPPED) {
            Row {
                TextButton(onRetry) { Text("Try again") }
                TextButton(onCopy) { Text("Copy cart ID") }
                if (details != null) TextButton({ showDetails = !showDetails }) { Text(if (showDetails) "Hide details" else "Details") }
            }
            if (details != null && showDetails) {
                Text(details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                TextButton(onCopyDetails) { Text("Copy details") }
            }
        }
    }
}

@Composable
fun CloseConfirmDialog(onClose: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        text = { Text(CLOSE_CONFIRM) },
        confirmButton = { TextButton(onClose) { Text("Close") } },
        dismissButton = { TextButton(onStay) { Text("Keep waiting") } },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoblawsScreen(cartId: String, prefs: Settings, onDone: () -> Unit) {
    if (!LoblawsHandoff.isCartId(cartId)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
            Text("That isn't a Loblaws cart. Open the cart again from the week and tap Open in Loblaws.")
            TextButton(onDone) { Text("Close") }
        }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val machine = remember(cartId) { HandoffMachine(cartId) }
    val clear = startClear(prefs.loblawsSignedOutStart, prefs.loblawsKeepDeviceTrust)
    var step by remember { mutableStateOf(machine.step) }
    var attempt by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }
    var pageHost by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }

    fun copy(label: String, text: String, toast: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
    }

    // Runs what the machine asked for; script results go back to the machine (which may ask for more).
    fun act(view: WebView, action: HandoffAction) {
        if (disposed) return
        val exec = { js: String ->
            view.evaluateJavascript(js) { res ->
                if (disposed) return@evaluateJavascript
                Log.d("MealPrepHandoff", "script result: $res")
                act(view, machine.onScriptResult(res))
            }
        }
        when (action) {
            is HandoffAction.Run -> exec(action.js)
            is HandoffAction.RunLater -> {
                val tried = attempt
                scope.launch { delay(action.delayMs); if (tried == attempt && machine.working) exec(action.js) }
            }
            HandoffAction.Reload -> view.reload()
            HandoffAction.None -> Unit
        }
        step = machine.step
    }

    fun start(wv: WebView) {
        machine.restart(); step = machine.step; attempt++
        val cm = CookieManager.getInstance()
        val load = {
            if (!disposed) {
                cm.setCookie(LoblawsHandoff.COOKIE_URL, LoblawsHandoff.cookie(cartId)); cm.flush()
                wv.loadUrl(LoblawsHandoff.START_URL)
            }
        }
        // Signed-out start = the only merge path confirmed (anonymous cart → sign in). Only Loblaws uses this WebView.
        when (clear) {
            StartClear.ALL -> { WebStorage.getInstance().deleteAllData(); cm.removeAllCookies { load() } }
            StartClear.SITE_ONLY -> {
                // Experimental: sign loblaws.ca out but keep accounts.pcid.ca's cookies ("remember this device").
                SiteReset.ORIGINS.forEach(WebStorage.getInstance()::deleteOrigin)
                val expire = SiteReset.plan(cm::getCookie)
                var left = expire.size
                if (left == 0) load()
                expire.forEach { (url, c) -> cm.setCookie(url, c) { if (--left == 0) { cm.flush(); load() } } }
            }
            StartClear.NONE -> load()
        }
    }

    // A page that never finishes (or a challenge loop) ends in Failed with Try again, not an endless spinner — but
    // only after one last look, since the cart may be in even if the page never reported it.
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        delay(LoblawsHandoff.TIMEOUT_MS)
        val last = machine.onTimeout(); step = machine.step
        web?.let { act(it, last) }
        delay(LoblawsHandoff.FINAL_CHECK_MS)
        machine.onTimeout(); step = machine.step
    }

    val requestClose = { if (closeNeedsConfirm(step)) confirmClose = true else onDone() }
    val details = if (step == HandoffStep.READY || step.working) null
        else handoffDetails(step, machine.stoppedAt, pageHost, machine.lastCheck, machine.checks, clear)

    // safeDrawing: the page and the bar above it stay clear of the status bar, camera cutout and navigation bar.
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        HandoffBanner(step, details, onRetry = { web?.let(::start) },
            onCopy = { copy("PC Express cart", cartId, "Cart ID copied") },
            onCopyDetails = { details?.let { copy("Loblaws handoff details", it, "Details copied") } },
            onClose = requestClose)
        AndroidView(modifier = Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)), factory = { c ->
            WebView(c).apply {
                // Compose already keeps this view clear of the system bars; without this the WebView also applies the
                // window's raw insets to the page itself and its top ends up hidden.
                ViewCompat.setOnApplyWindowInsetsListener(this) { _, _ -> WindowInsetsCompat.CONSUMED }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                if (prefs.loblawsHideWebViewMarker) settings.userAgentString = LoblawsHandoff.stripWebViewMarker(settings.userAgentString)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !LoblawsHandoff.isWebUrl(request.url.toString())

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) { machine.onLoadError(); step = machine.step }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        if (disposed) return
                        pageHost = LoblawsHandoff.host(url)
                        act(view, machine.onPageFinished(url, view.title))
                    }
                }
                web = this
                post { start(this) }   // after composition: start() updates screen state
            }
        })
    }
    if (confirmClose) CloseConfirmDialog(onClose = { confirmClose = false; onDone() }, onStay = { confirmClose = false })
    // Back walks back through her sign-in pages first; at the start it leaves the screen (asking first while working).
    BackHandler(enabled = canGoBack) { web?.goBack() }
    BackHandler(enabled = !canGoBack && closeNeedsConfirm(step)) { confirmClose = true }
    DisposableEffect(Unit) { onDispose { disposed = true; web?.apply { stopLoading(); destroy() } } }
}

/** Settings section (Task 6's SettingsScreen `extra`). */
@Composable
fun LoblawsSettings(graph: AppGraph) {
    val s by graph.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LoblawsSettingsContent(s,
        onSignedOut = { v -> scope.launch { graph.settingsStore.update { it.copy(loblawsSignedOutStart = v) } } },
        onHideMarker = { v -> scope.launch { graph.settingsStore.update { it.copy(loblawsHideWebViewMarker = v) } } },
        onKeepTrust = { v -> scope.launch { graph.settingsStore.update { it.copy(loblawsKeepDeviceTrust = v) } } })
}

@Composable
fun LoblawsSettingsContent(s: Settings, onSignedOut: (Boolean) -> Unit, onHideMarker: (Boolean) -> Unit, onKeepTrust: (Boolean) -> Unit = {}) {
    Column {
        Text("Loblaws", style = MaterialTheme.typography.titleMedium)
        SwitchRow("Sign out before loading the cart (recommended)", null, s.loblawsSignedOutStart, onSignedOut)
        SwitchRow("Keep PC id device trust (experimental)",
            if (s.loblawsSignedOutStart) "Signs out of loblaws.ca only, so PC id may remember this phone and skip the code. " +
                "If the cart doesn't move into your account, turn this off."
            else "Only used with “Sign out before loading the cart”.",
            s.loblawsKeepDeviceTrust, onKeepTrust, enabled = s.loblawsSignedOutStart)
        SwitchRow("Hide in-app browser marker (if Loblaws blocks the page)", null, s.loblawsHideWebViewMarker, onHideMarker)
    }
}

/** A whole-row switch (48 dp, one control for TalkBack). */
@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
