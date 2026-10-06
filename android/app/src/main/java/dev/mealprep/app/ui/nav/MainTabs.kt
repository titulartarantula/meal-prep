package dev.mealprep.app.ui.nav

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import dev.mealprep.app.R
import dev.mealprep.app.ui.theme.GardenAccent

/** The three main screens behind the bottom bar. The Shopping list is there whatever state the week's cart is in. */
enum class Tab(val label: String, @param:DrawableRes val icon: Int, val route: Any) {
    WEEK("This week", R.drawable.ic_calendar, HomeRoute()),
    LIST("Shopping list", R.drawable.ic_cart, ListRoute("")),
    RECIPES("Recipes", R.drawable.ic_book, LibraryRoute),
}

/** The tab a screen belongs to (the bar shows only on these), or null. */
fun tabOf(d: NavDestination?): Tab? = when {
    d == null -> null
    d.hasRoute<HomeRoute>() -> Tab.WEEK
    d.hasRoute<ListRoute>() -> Tab.LIST
    d.hasRoute<LibraryRoute>() -> Tab.RECIPES
    else -> null
}

/** Switch tabs: one copy of each, the week at the bottom, each tab's own back stack kept (save/restore). */
fun NavController.openTab(t: Tab) = navigate(t.route) {
    popUpTo<HomeRoute> { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun MainTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        val colors = GardenAccent.navItemColors()
        Tab.entries.forEach { t ->
            // The label names the tab, so the icon itself is decoration for TalkBack.
            NavigationBarItem(selected = t == selected, onClick = { onSelect(t) },
                icon = { Icon(painterResource(t.icon), contentDescription = null) }, label = { Text(t.label) }, colors = colors)
        }
    }
}
