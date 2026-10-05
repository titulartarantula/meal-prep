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
        assertEquals(RatingRoute(21, "2026-10-11"), Nav.parse(Nav.rating(21, wk)))
        assertEquals(CardRoute(21), Nav.parse(Nav.card(21)))
        assertEquals(DraftRoute(7), Nav.parse(Nav.draft(7)))
        assertEquals(PrepRoute("2026-10-11"), Nav.parse(Nav.prep(wk)))
    }

    @Test fun `garbage is ignored`() {
        assertNull(Nav.parse(null)); assertNull(Nav.parse("card/x")); assertNull(Nav.parse("nope/1"))
    }

    @Test fun `context buttons without a screen yet say so`() {
        val wk = LocalDate.parse("2026-10-11")
        assertNull(contextRoute(ContextAction.BuildCart(wk)))
        assertEquals("That arrives in a later update.", contextToast(ContextAction.BuildCart(wk)))
        assertEquals("Share a recipe from NYT Cooking to add it.", contextToast(ContextAction.AddRecipes))
        assertNull(contextToast(ContextAction.AllSet))
        assertNull(contextToast(ContextAction.StartPrep(wk)))   // has a route: navigating handles it
    }
}
