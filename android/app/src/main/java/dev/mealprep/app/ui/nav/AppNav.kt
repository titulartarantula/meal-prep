package dev.mealprep.app.ui.nav

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.mealprep.app.ui.library.LibraryScreen
import dev.mealprep.app.ui.library.RecipeScreen
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.ui.camera.CameraScreen
import dev.mealprep.app.ui.card.CardScreen
import dev.mealprep.app.ui.camera.CameraViewModel
import dev.mealprep.app.ui.cart.DraftScreen
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.cart.ListScreen
import dev.mealprep.app.ui.cart.StaplesScreen
import dev.mealprep.app.ui.setup.NotifSettings
import dev.mealprep.app.ui.rating.RatingScreen
import dev.mealprep.app.work.SyncWorker
import dev.mealprep.app.ui.home.ContextAction
import dev.mealprep.app.ui.loblaws.LoblawsScreen
import dev.mealprep.app.ui.loblaws.LoblawsSettings
import dev.mealprep.app.ui.home.HomeScreen
import dev.mealprep.app.ui.prep.PrepScreen
import dev.mealprep.app.ui.setup.SettingsScreen
import dev.mealprep.app.ui.exchange.ImportScreen
import dev.mealprep.app.ui.share.ShareScreen
import java.time.LocalDate
import dev.mealprep.app.ui.setup.SetupScreen

@Composable
fun AppNav(nav: NavHostController, start: Any, graph: AppGraph) {
    val entry by nav.currentBackStackEntryAsState()
    val tab = tabOf(entry?.destination)
    val ctx = LocalContext.current
    val open: (Any) -> Unit = { r -> runCatching { nav.navigate(r) }.onFailure { Toast.makeText(ctx, LATER, Toast.LENGTH_SHORT).show() } }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) { Screens(nav, start, graph, open) }
        tab?.let { t -> MainTabs(t) { nav.openTab(it) } }
    }
}

@Composable
private fun Screens(nav: NavHostController, start: Any, graph: AppGraph, open: (Any) -> Unit) {
    // The top bar's Back on every screen that isn't a tab (and the screens' own "leave" after saving).
    val up: () -> Unit = { if (!nav.popBackStack()) nav.navigate(HomeRoute()) }
    NavHost(nav, startDestination = start) {
        composable<SetupRoute> {
            SetupScreen(graph, onDone = { nav.navigate(HomeRoute()) { popUpTo<SetupRoute> { inclusive = true } } })
        }
        composable<SettingsRoute> {
            SettingsScreen(graph, onDone = up) { NotifSettings(graph); LoblawsSettings(graph) }
        }
        composable<StaplesRoute> { StaplesScreen(onBack = up) }
        composable<HomeRoute> { back ->
            HomeScreen(graph, back.toRoute<HomeRoute>().week?.let(LocalDate::parse),
                onAction = { a ->
                    when (val route = contextRoute(a)) {
                        null -> {}
                        LibraryRoute -> nav.openTab(Tab.RECIPES)   // the Recipes tab, not a second copy on top
                        else -> open(route)
                    }
                },
                onOpen = open,
                menu = mainMenu())
        }
        composable<ListRoute> { back ->
            val weeks = back.toRoute<ListRoute>().weeks.split(",").filter { it.isNotBlank() }
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            ListScreen(weeks, onDraft = { id -> nav.navigate(DraftRoute(id)) { popUpTo<ListRoute> { inclusive = true } } },
                onOpenDraft = { id -> nav.navigate(DraftRoute(id)) }, onEditStaples = { nav.navigate(StaplesRoute) },
                menu = mainMenu(), onOpen = open)
        }
        composable<LibraryRoute> {
            LibraryScreen(mainMenu(), open, onRecipe = { id -> nav.navigate(RecipeRoute(id)) },
                onPhotos = { uris -> graph.pendingShare.value = ShareInput.Photos(uris); nav.navigate(ShareRoute) },
                onLink = { url -> graph.imports.enqueueLink(url) })
        }
        composable<RecipeRoute> { back ->
            val r = back.toRoute<RecipeRoute>()
            RecipeScreen(r.id, addToWeek = r.addToWeek, onOpen = open,
                onWeek = { w -> nav.navigate(HomeRoute(w.toString())) { popUpTo<HomeRoute> { inclusive = true } } },
                onBack = up)
        }
        composable<DraftRoute> { back ->
            DraftScreen(back.toRoute<DraftRoute>().id,
                onLoblaws = { cart -> nav.navigate(LoblawsRoute(cart)) },
                onRebuild = { weeks -> nav.navigate(ListRoute(weeks.joinToString(","))) { popUpTo<DraftRoute> { inclusive = true } } },
                onBack = up)
        }
        composable<LoblawsRoute> { back ->
            val prefs by graph.settings.collectAsStateWithLifecycle()
            LoblawsScreen(back.toRoute<LoblawsRoute>().cartId, prefs,
                onDone = { nav.navigate(HomeRoute()) { popUpTo<HomeRoute> { inclusive = true } } })
        }
        composable<PrepRoute> { back ->
            PrepScreen(LocalDate.parse(back.toRoute<PrepRoute>().week), onCard = { nav.navigate(CardRoute(it)) }, onBack = up)
        }
        composable<CardRoute> { back -> CardScreen(back.toRoute<CardRoute>().entryId, onOpen = open, onBack = up) }
        composable<RatingRoute> { back ->
            val r = back.toRoute<RatingRoute>()
            RatingScreen(r.entryId, LocalDate.parse(r.week),
                afterChange = { graph.notifier.rated(r.entryId); SyncWorker.now(graph.workManager) },
                onDone = up)
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
                onBack = up)
        }
        composable<ImportRoute> { back ->
            ImportScreen(back.toRoute<ImportRoute>(), onBack = up, onRecipe = { id -> nav.navigate(RecipeRoute(id)) },
                // The new recipes are at the top of Recipes (newest first; the tab reloads when it shows).
                onDone = { nav.popBackStack(); nav.openTab(Tab.RECIPES) })
        }
        composable<ShareRoute> {
            ShareScreen(graph,
                // Saved recipes land in Recipes, where the import card shows its progress.
                onQueued = { nav.popBackStack(); nav.openTab(Tab.RECIPES) },
                onBack = up,
                onSetup = { nav.navigate(SetupRoute) })
        }
    }
}

fun cameraTitle(r: CameraRoute): String = when {
    r.purpose != "ref" -> "Photograph the recipe, page by page"
    r.page > 0 -> "Photograph page ${r.page} (the part this recipe refers to)"
    else -> "Photograph the page this recipe refers to"
}

/** Where the home screen's context button goes (null = nothing to open). An empty week opens the Recipes tab:
 *  weeks are planned from the library. */
fun contextRoute(a: ContextAction): Any? = when (a) {
    ContextAction.AddRecipes -> LibraryRoute
    is ContextAction.BuildCart -> ListRoute(a.week.toString())
    is ContextAction.ReviewCart -> DraftRoute(a.draftId)
    is ContextAction.StartPrep -> PrepRoute(a.week.toString())
    is ContextAction.ContinuePrep -> PrepRoute(a.week.toString())
    is ContextAction.Tonight -> CardRoute(a.entryId)
    ContextAction.AllSet -> null
}

const val LATER = "That arrives in a later update."

/** The ⋮ menu on the main screens (label to route); the bottom bar has This week, Shopping and Recipes, and
 *  adding recipes lives on Recipes (Add recipe). */
fun mainMenu(): List<Pair<String, Any>> = listOf("Staples" to StaplesRoute, "Settings" to SettingsRoute)

/** A link from a notification: a tab opens as that tab (no second copy); anything else goes on top. */
fun NavController.openLink(route: Any) {
    val t = Tab.entries.firstOrNull { it.route::class == route::class && route !is HomeRoute }
    when {
        t != null -> navigate(route) { popUpTo<HomeRoute>(); launchSingleTop = true }
        route is ImportRoute -> { openTab(Tab.RECIPES); navigate(route) }   // an import lands under Recipes
        else -> navigate(route)
    }
}
