package dev.mealprep.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG 2.x AA for every text/background pair the screens use, light and dark. */
class GardenContrastTest {
    private fun lum(c: Color) = listOf(c.red, c.green, c.blue)
        .map { if (it <= 0.03928f) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        .let { (r, g, b) -> 0.2126 * r + 0.7152 * g + 0.0722 * b }

    private fun ratio(a: Color, b: Color) = (max(lum(a), lum(b)) + 0.05) / (min(lum(a), lum(b)) + 0.05)

    private fun pairs(s: ColorScheme, a: Accent) = listOf(
        Triple("body text", s.onSurface to s.surface, 4.5),
        Triple("secondary text", s.onSurfaceVariant to s.surface, 4.5),
        Triple("text in cards", s.onSurface to s.surfaceContainerHighest, 4.5),
        Triple("secondary text in cards", s.onSurfaceVariant to s.surfaceContainerHighest, 4.5),
        Triple("dialog text", s.onSurfaceVariant to s.surfaceContainerHigh, 4.5),
        Triple("dialog buttons", s.primary to s.surfaceContainerHigh, 4.5),
        Triple("menus", s.onSurface to s.surfaceContainer, 4.5),
        Triple("nav bar labels", s.onSurfaceVariant to s.surfaceContainer, 4.5),
        Triple("accent text & links", s.primary to s.surface, 4.5),
        Triple("accent text in cards", s.primary to s.surfaceContainerHighest, 4.5),
        Triple("filled button", s.onPrimary to s.primary, 4.5),
        Triple("drop zone, FAB", s.onPrimaryContainer to s.primaryContainer, 4.5),
        Triple("secondary container", s.onSecondaryContainer to s.secondaryContainer, 4.5),
        Triple("offline banner", s.onTertiaryContainer to s.tertiaryContainer, 4.5),
        Triple("errors", s.error to s.surface, 4.5),
        Triple("errors in cards", s.error to s.surfaceContainerHighest, 4.5),
        Triple("error container", s.onErrorContainer to s.errorContainer, 4.5),
        Triple("inverse", s.inverseOnSurface to s.inverseSurface, 4.5),
        Triple("selected tab label", a.text to s.surfaceContainer, 4.5),
        Triple("selected tab pill, chips", a.onContainer to a.container, 4.5),
        Triple("checkbox, progress", s.primary to s.surface, 3.0),
        Triple("progress on its track", s.primary to s.secondaryContainer, 3.0),
        Triple("field & chip outlines", s.outline to s.surface, 3.0),
        Triple("field outlines in dialogs", s.outline to s.surfaceContainerHigh, 3.0),
        Triple("advice (prep warnings)", s.onTertiaryContainer to s.tertiaryContainer, 4.5),
    )

    private fun check(name: String, s: ColorScheme, a: Accent) = pairs(s, a).forEach { (what, fb, need) ->
        val r = ratio(fb.first, fb.second)
        assertTrue("$name $what: ${"%.2f".format(r)} < $need", r >= need)
    }

    @Test fun `light meets AA`() = check("light", GardenLight, LightAccent)

    @Test fun `dark meets AA`() = check("dark", GardenDark, DarkAccent)

    /**
     * WCAG 1.4.11 for chip states, on the page (surface) and in dialogs (surfaceContainerHigh): each chip's edge holds
     * 3:1 against what is around it, and what only the selected chip has (its check mark, on its fill) holds 3:1
     * against both its own fill and the plain background an unselected chip shows there. Before 0.7.2 the selected
     * fill alone (1.0:1 in light dialogs) and an outlineVariant edge (1.4:1) told the states apart.
     */
    private fun chipPairs(s: ColorScheme, a: Accent) = listOf("page" to s.surface, "dialog" to s.surfaceContainerHigh).flatMap { (where, bg) ->
        listOf(
            Triple("unselected chip edge on the $where", chipEdge(s, a, selected = false) to bg, 3.0),
            Triple("selected chip edge on the $where", chipEdge(s, a, selected = true) to bg, 3.0),
            Triple("check mark vs an unselected chip on the $where", a.onContainer to bg, 3.0),
        )
    } + Triple("check mark on the selected fill", a.onContainer to a.container, 3.0)

    private fun checkChips(name: String, s: ColorScheme, a: Accent) = chipPairs(s, a).forEach { (what, fb, need) ->
        val r = ratio(fb.first, fb.second)
        assertTrue("$name $what: ${"%.2f".format(r)} < $need", r >= need)
    }

    @Test fun `light chip states are told apart at 3 to 1`() = checkChips("light", GardenLight, LightAccent)

    @Test fun `dark chip states are told apart at 3 to 1`() = checkChips("dark", GardenDark, DarkAccent)

    @Test fun `a selected chip also has a thicker edge, not only another colour`() {
        assertTrue(CHIP_EDGE_SELECTED > CHIP_EDGE)
        // The old edge (Material's default outlineVariant) is the one that failed.
        assertTrue(ratio(GardenLight.outlineVariant, GardenLight.surface) < 3.0)
    }

    @Test fun `the ratio matches the WCAG reference values`() {
        assertEquals(21.0, ratio(Color.Black, Color.White), 0.01)
        assertEquals(5.89, ratio(Color(0xFF3A6B35), Color(0xFFFAF7F2)), 0.01)   // brand green on linen
    }
}
