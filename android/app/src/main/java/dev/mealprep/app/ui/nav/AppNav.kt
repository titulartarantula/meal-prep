package dev.mealprep.app.ui.nav

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.ui.camera.CameraScreen
import dev.mealprep.app.ui.camera.CameraViewModel
import dev.mealprep.app.ui.cart.DraftScreen
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.cart.ListScreen
import dev.mealprep.app.ui.home.ContextAction
import dev.mealprep.app.ui.loblaws.LoblawsScreen
import dev.mealprep.app.ui.loblaws.LoblawsSettings
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
        composable<SettingsRoute> { SettingsScreen(graph, onDone = { nav.popBackStack() }) { LoblawsSettings(graph) } }
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
        composable<ListRoute> { back ->
            val weeks = back.toRoute<ListRoute>().weeks.split(",").filter { it.isNotBlank() }
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            ListScreen(weeks, onDraft = { id -> nav.navigate(DraftRoute(id)) { popUpTo<ListRoute> { inclusive = true } } })
        }
        composable<DraftRoute> { back ->
            DraftScreen(back.toRoute<DraftRoute>().id,
                onLoblaws = { cart -> nav.navigate(LoblawsRoute(cart)) },
                onRebuild = { weeks -> nav.navigate(ListRoute(weeks.joinToString(","))) { popUpTo<DraftRoute> { inclusive = true } } })
        }
        composable<LoblawsRoute> { back ->
            val prefs by graph.settings.collectAsStateWithLifecycle()
            LoblawsScreen(back.toRoute<LoblawsRoute>().cartId, prefs,
                onDone = { nav.navigate(HomeRoute()) { popUpTo<HomeRoute> { inclusive = true } } })
        }
        composable<CameraRoute> { back ->
            val r = back.toRoute<CameraRoute>()
            val vm = graphViewModel(key = "camera-${back.id}") { g -> CameraViewModel(g.pages, g.scalePage, g.copyPage) }
            CameraScreen(vm, title = cameraTitle(r),
                onDone = { dir ->
                    if (r.purpose == "ref") {
                        // Read in the background like any import; the home screen shows "Reading the referenced page…".
                        graph.imports.enqueuePages(dir, r.recipeId, r.forLine, r.page)
                        if (!nav.popBackStack()) nav.navigate(HomeRoute())
                    } else {
                        graph.pendingShare.value = ShareInput.Pages(dir)
                        nav.navigate(ShareRoute) { popUpTo<CameraRoute> { inclusive = true } }
                    }
                },
                onCancel = { if (!nav.popBackStack()) nav.navigate(HomeRoute()) })
        }
        composable<ShareRoute> {
            ShareScreen(graph,
                onQueued = { week -> nav.navigate(HomeRoute(week.toString())) { popUpTo<ShareRoute> { inclusive = true } } },
                onCancel = { if (!nav.popBackStack()) nav.navigate(HomeRoute()) },
                onSetup = { nav.navigate(SetupRoute) })
        }
    }
}

fun cameraTitle(r: CameraRoute): String = when {
    r.purpose != "ref" -> "Photograph the recipe, page by page"
    r.page > 0 -> "Photograph page ${r.page} (the part this recipe refers to)"
    else -> "Photograph the page this recipe refers to"
}

/** Where the home screen's context button goes (null = nothing to open). */
fun contextRoute(a: ContextAction): Any? = when (a) {
    is ContextAction.BuildCart -> ListRoute(a.week.toString())
    is ContextAction.ReviewCart -> DraftRoute(a.draftId)
    is ContextAction.StartPrep -> PrepRoute(a.week.toString())
    is ContextAction.ContinuePrep -> PrepRoute(a.week.toString())
    is ContextAction.Tonight -> CardRoute(a.entryId)
    ContextAction.AddRecipes, ContextAction.AllSet -> null
}

/** What tapping a context button with no screen to open says (null = it opens [contextRoute], or does nothing). */
fun contextToast(a: ContextAction): String? = when (a) {
    ContextAction.AddRecipes -> "Share a recipe from NYT Cooking or a photo of a cookbook page, or use Menu → Snap a cookbook recipe."
    else -> null
}

const val LATER = "That arrives in a later update."

/** Home "Menu" items (label to route). */
fun homeMenu(): List<Pair<String, Any>> =
    listOf("Snap a cookbook recipe" to CameraRoute(), "Shopping list" to ListRoute(""), "Settings" to SettingsRoute)
