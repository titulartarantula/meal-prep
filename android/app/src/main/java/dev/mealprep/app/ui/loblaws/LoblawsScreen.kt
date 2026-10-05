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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

@Composable
fun HandoffBanner(step: HandoffStep, onRetry: () -> Unit, onCopy: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text(bannerText(step), style = MaterialTheme.typography.bodyMedium)
        if (step in setOf(HandoffStep.LOADING, HandoffStep.INJECTING, HandoffStep.RELOADING, HandoffStep.VERIFYING)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Row {
            if (step == HandoffStep.BLOCKED || step == HandoffStep.FAILED || step == HandoffStep.UNREACHABLE) {
                TextButton(onRetry) { Text("Try again") }
                TextButton(onCopy) { Text("Copy cart ID") }
            }
            TextButton(onDone) { Text("Done") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoblawsScreen(cartId: String, prefs: Settings, onDone: () -> Unit) {
    if (!LoblawsHandoff.isCartId(cartId)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("That isn't a Loblaws cart. Open the cart again from the week and tap Open in Loblaws.")
            TextButton(onDone) { Text("Done") }
        }
        return
    }
    val ctx = LocalContext.current
    val machine = remember(cartId) { HandoffMachine(cartId) }
    var step by remember { mutableStateOf(machine.step) }
    var attempt by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }

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
        if (prefs.loblawsSignedOutStart) { WebStorage.getInstance().deleteAllData(); cm.removeAllCookies { load() } } else load()
    }

    // A page that never finishes (or a challenge loop) ends in Failed with Try again, not an endless spinner.
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        delay(LoblawsHandoff.TIMEOUT_MS)
        machine.onTimeout(); step = machine.step
    }

    Column(Modifier.fillMaxSize()) {
        HandoffBanner(step, onRetry = { web?.let(::start) }, onCopy = {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PC Express cart", cartId))
            Toast.makeText(ctx, "Cart ID copied", Toast.LENGTH_SHORT).show()
        }, onDone = onDone)
        AndroidView(modifier = Modifier.weight(1f), factory = { c ->
            WebView(c).apply {
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
                        when (val action = machine.onPageFinished(url, view.title)) {
                            is HandoffAction.Run -> view.evaluateJavascript(action.js) { res ->
                                if (disposed) return@evaluateJavascript
                                Log.d("MealPrepHandoff", "script result: $res")
                                if (machine.onScriptResult(res) == HandoffAction.Reload) view.reload()
                                step = machine.step
                            }
                            HandoffAction.Reload -> view.reload()
                            HandoffAction.None -> Unit
                        }
                        step = machine.step
                    }
                }
                web = this
                post { start(this) }   // after composition: start() updates screen state
            }
        })
    }
    // Back walks back through her sign-in pages first; at the start it leaves the screen.
    BackHandler(enabled = canGoBack) { web?.goBack() }
    DisposableEffect(Unit) { onDispose { disposed = true; web?.apply { stopLoading(); destroy() } } }
}

/** Settings section (Task 6's SettingsScreen `extra`). */
@Composable
fun LoblawsSettings(graph: AppGraph) {
    val s by graph.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LoblawsSettingsContent(s,
        onSignedOut = { v -> scope.launch { graph.settingsStore.update { it.copy(loblawsSignedOutStart = v) } } },
        onHideMarker = { v -> scope.launch { graph.settingsStore.update { it.copy(loblawsHideWebViewMarker = v) } } })
}

@Composable
fun LoblawsSettingsContent(s: Settings, onSignedOut: (Boolean) -> Unit, onHideMarker: (Boolean) -> Unit) {
    Column {
        Text("Loblaws", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sign out before loading the cart (recommended)", Modifier.weight(1f))
            Switch(s.loblawsSignedOutStart, onSignedOut)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hide in-app browser marker (if Loblaws blocks the page)", Modifier.weight(1f))
            Switch(s.loblawsHideWebViewMarker, onHideMarker)
        }
    }
}
