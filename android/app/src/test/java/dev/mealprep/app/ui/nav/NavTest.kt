package dev.mealprep.app.ui.nav

import dev.mealprep.app.ui.home.ContextAction
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavTest {
    @Test fun `deep links round trip`() {
        val wk = LocalDate.parse("2026-10-11")
        assertEquals(HomeRoute("2026-10-11"), Nav.parse(Nav.home(wk)))
        assertEquals(HomeRoute(null), Nav.parse(Nav.home(null)))
        assertEquals(CameraRoute("ref", 3, 1), Nav.parse(Nav.ref(3, 1)))
        assertEquals(CameraRoute("ref", 3, 1, 191), Nav.parse(Nav.ref(3, 1, 191)))
        assertEquals(CameraRoute("ref", 3, 1), Nav.parse("ref/3/1"))                // links from 0.2.x notifications
        assertEquals(RatingRoute(21, "2026-10-11"), Nav.parse(Nav.rating(21, wk)))
        assertEquals(CardRoute(21), Nav.parse(Nav.card(21)))
        assertEquals(DraftRoute(7), Nav.parse(Nav.draft(7)))
        assertEquals(PrepRoute("2026-10-11"), Nav.parse(Nav.prep(wk)))
        assertEquals(ListRoute(""), Nav.parse(Nav.list()))
        assertEquals(RecipeRoute(4), Nav.parse(Nav.recipe(4)))
        assertEquals(RecipeRoute(4, addToWeek = true), Nav.parse(Nav.recipe(4, addToWeek = true)))
    }

    @Test fun `the overflow menu keeps the extras, the bottom bar the main screens`() {
        assertEquals(listOf("Staples" to StaplesRoute, "Settings" to SettingsRoute), mainMenu())   // adding recipes is on Recipes
        assertEquals(listOf("This week", "Shopping", "Recipes"), Tab.entries.map { it.label })   // "Shopping list" wrapped at 200 %
        assertEquals(listOf(HomeRoute(), ListRoute(""), LibraryRoute), Tab.entries.map { it.route })
    }

    @Test fun `garbage is ignored`() {
        assertNull(Nav.parse(null)); assertNull(Nav.parse("card/x")); assertNull(Nav.parse("nope/1"))
    }

    @Test fun `context buttons open their screen, an empty week the Recipes tab`() {
        val wk = LocalDate.parse("2026-10-11")
        assertEquals(ListRoute("2026-10-11"), contextRoute(ContextAction.BuildCart(wk)))
        assertEquals(LibraryRoute, contextRoute(ContextAction.AddRecipes))
        assertEquals("Choose from Recipes", ContextAction.AddRecipes.label)
        assertNull(contextRoute(ContextAction.AllSet))
    }

    @Test fun `camera titles say what to photograph`() {
        assertEquals("Photograph the recipe, page by page", cameraTitle(CameraRoute()))
        assertEquals("Photograph page 191 (the part this recipe refers to)", cameraTitle(CameraRoute("ref", 3, 1, 191)))
        assertEquals("Photograph the page this recipe refers to", cameraTitle(CameraRoute("ref", 3, 1)))
    }
}
