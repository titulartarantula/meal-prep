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
    }

    @Test fun `menu opens the shopping list for the default week`() {
        assertEquals(listOf("Snap a cookbook recipe" to CameraRoute(), "Shopping list" to ListRoute(""), "Settings" to SettingsRoute), homeMenu())
    }

    @Test fun `garbage is ignored`() {
        assertNull(Nav.parse(null)); assertNull(Nav.parse("card/x")); assertNull(Nav.parse("nope/1"))
    }

    @Test fun `context buttons without a screen yet say so`() {
        val wk = LocalDate.parse("2026-10-11")
        assertEquals(ListRoute("2026-10-11"), contextRoute(ContextAction.BuildCart(wk)))
        assertNull(contextToast(ContextAction.BuildCart(wk)))
        assertEquals("Share a recipe from NYT Cooking or a photo of a cookbook page, or use Menu → Snap a cookbook recipe.",
            contextToast(ContextAction.AddRecipes))
        assertNull(contextToast(ContextAction.AllSet))
        assertNull(contextToast(ContextAction.StartPrep(wk)))   // has a route: navigating handles it
    }

    @Test fun `camera titles say what to photograph`() {
        assertEquals("Photograph the recipe, page by page", cameraTitle(CameraRoute()))
        assertEquals("Photograph page 191 (the part this recipe refers to)", cameraTitle(CameraRoute("ref", 3, 1, 191)))
        assertEquals("Photograph the page this recipe refers to", cameraTitle(CameraRoute("ref", 3, 1)))
    }
}
