package dev.mealprep.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.core.ShareParser
import dev.mealprep.app.ui.nav.importRoute
import kotlinx.coroutines.launch
import dev.mealprep.app.ui.nav.AppNav
import dev.mealprep.app.ui.nav.HomeRoute
import dev.mealprep.app.ui.nav.Nav
import dev.mealprep.app.ui.nav.SignInRoute
import dev.mealprep.app.ui.account.SignInAgainDialog
import dev.mealprep.app.ui.account.rememberSignIn
import dev.mealprep.app.ui.common.Access
import dev.mealprep.app.ui.common.LocalAccess
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.ui.nav.openLink
import dev.mealprep.app.ui.nav.ShareRoute
import dev.mealprep.app.ui.theme.MealPrepTheme
import dev.mealprep.app.work.SyncWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val navEvents = Channel<Any>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start: Any = if (graph.settings.value.configured) HomeRoute() else SignInRoute
        if (savedInstanceState == null) handle(intent)
        setContent {
            MealPrepTheme {
                // The Surface fills the whole window (behind the system bars too); only its content keeps clear of them.
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        val nav = rememberNavController()
                        // What this person may do (GET /auth/me, saved): screens hide the rest, as the web does.
                        val prefs by graph.settings.collectAsStateWithLifecycle()
                        CompositionLocalProvider(LocalAccess provides Access(prefs.account?.permissions, prefs.account?.memberId)) {
                            AppNav(nav, start, graph)
                        }
                        // A signed-in call was refused: the sign-in ended; offer to sign in again.
                        val needsSignIn by graph.accounts.needsSignIn.collectAsStateWithLifecycle()
                        val signIn = rememberSignIn(graph)
                        if (needsSignIn) SignInAgainDialog(
                            onSignIn = { graph.accounts.dismissSignInPrompt(); signIn(null) },
                            onLater = graph.accounts::dismissSignInPrompt)
                        // runCatching: a deep link to a screen this build doesn't have yet must not crash the app.
                        LaunchedEffect(Unit) { navEvents.receiveAsFlow().collect { runCatching { nav.openLink(it) } } }
                    }
                }
            }
        }
    }

    /** Each time the app comes to the front: fresh copies and reminders now (the other phone may have changed the
     *  week), and the hourly sync kept queued (it carries on while the app is closed). */
    override fun onStart() {
        super.onStart()
        if (!graph.settings.value.configured) return
        // Roles may have changed on the web (or another household was picked there): read them again.
        lifecycleScope.launch { graph.accounts.refresh() }
        SyncWorker.schedule(graph.workManager)
        SyncWorker.now(graph.workManager)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        if (isReplayFromHistory(intent)) return
        when (val share = ShareParser.parse(intent)) {
            null -> {}
            // A recipe file: copied in now, while this activity holds the read grant, then the import screen.
            is ShareInput.RecipeFile -> { lifecycleScope.launch { navEvents.send(importRoute(graph.copyImport(share.uri))) }; return }
            else -> { graph.pendingShare.value = share; navEvents.trySend(ShareRoute); return }
        }
        Nav.parse(intent.getStringExtra(Nav.EXTRA))?.let { navEvents.trySend(it) }
    }
}

/** Reopening from Recents re-delivers the original intent; it must not replay an old share or notification link. */
internal fun isReplayFromHistory(intent: Intent) = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
