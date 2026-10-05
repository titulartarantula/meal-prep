package dev.mealprep.app.ui.nav

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.mealprep.app.AppGraph
import dev.mealprep.app.ui.home.ContextAction
import dev.mealprep.app.ui.home.HomeScreen
import dev.mealprep.app.ui.setup.SettingsScreen
import dev.mealprep.app.ui.share.ShareScreen
import java.time.LocalDate
import dev.mealprep.app.ui.setup.SetupScreen

@Composable
fun AppNav(nav: NavHostController, start: Any, graph: AppGraph) {
    NavHost(nav, startDestination = start) {
        composable<SetupRoute> {
            SetupScreen(graph, onDone = { nav.navigate(HomeRoute()) { popUpTo<SetupRoute> { inclusive = true } } })
        }
        composable<SettingsRoute> { SettingsScreen(graph, onDone = { nav.popBackStack() }) }
        composable<HomeRoute> { back ->
            val ctx = LocalContext.current
            val open: (Any) -> Unit = { r -> runCatching { nav.navigate(r) }.onFailure {
                Toast.makeText(ctx, LATER, Toast.LENGTH_SHORT).show() } }
            HomeScreen(graph, back.toRoute<HomeRoute>().week?.let(LocalDate::parse),
                onAction = { a ->
                    when (val route = contextRoute(a)) {
                        null -> contextToast(a)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                        else -> open(route)
                    }
                },
                onOpen = open,
                menu = homeMenu())
        }
        composable<ShareRoute> {
            ShareScreen(graph,
                onQueued = { week -> nav.navigate(HomeRoute(week.toString())) { popUpTo<ShareRoute> { inclusive = true } } },
                onCancel = { if (!nav.popBackStack()) nav.navigate(HomeRoute()) },
                onSetup = { nav.navigate(SetupRoute) })
        }
    }
}

/** Where the home screen's context button goes (null = nothing to open). */
fun contextRoute(a: ContextAction): Any? = when (a) {
    is ContextAction.BuildCart -> null        // Task 10
    is ContextAction.ReviewCart -> DraftRoute(a.draftId)
    is ContextAction.StartPrep -> PrepRoute(a.week.toString())
    is ContextAction.ContinuePrep -> PrepRoute(a.week.toString())
    is ContextAction.Tonight -> CardRoute(a.entryId)
    ContextAction.AddRecipes, ContextAction.AllSet -> null
}

/** What tapping a context button with no screen to open says (null = it opens [contextRoute], or does nothing). */
fun contextToast(a: ContextAction): String? = when (a) {
    ContextAction.AddRecipes -> "Share a recipe from NYT Cooking to add it."
    is ContextAction.BuildCart -> LATER
    else -> null
}

const val LATER = "That arrives in a later update."

/** Home "Menu" items (label to route). */
fun homeMenu(): List<Pair<String, Any>> = listOf("Settings" to SettingsRoute)
