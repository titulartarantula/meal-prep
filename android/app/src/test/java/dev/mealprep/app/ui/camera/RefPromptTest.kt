package dev.mealprep.app.ui.camera

import dev.mealprep.app.data.api.Ingredient
import dev.mealprep.app.data.api.Recipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefPromptTest {
    private fun ing(raw: String, page: Int? = null, expanded: Boolean = false) = Ingredient(raw, raw, refPage = page, expanded = expanded)

    // Synthetic lines in the shape the server returns (not copied from any cookbook).
    private val r = Recipe(9, "Filled Crêpes", "photo", ingredients = listOf(
        ing("Butter"), ing("Batter for 12 crêpes, page 40", null, expanded = true), ing("2 eggs"),
        ing("Orange butter, page 44", 44), ing("Sauce, page 51", 51)))

    @Test fun `first unattached page reference is offered`() {
        val p = refPrompt(r)!!
        assertEquals(RefPrompt(9, 3, "Orange butter, page 44", 44), p)
        assertEquals("This uses “Orange butter, page 44” — add a photo of page 44?", refPromptText(p))
    }

    @Test fun `nothing to offer`() {
        assertNull(refPrompt(Recipe(1, "x", "nyt", ingredients = listOf(ing("Salt")))))
        assertNull(refPrompt(Recipe(1, "x", "photo", ingredients = listOf(ing("Batter, page 40", null, expanded = true)))))
    }

    @Test fun `a line number that moved is found again by its page`() {
        assertEquals(3, resolveRefLine(r, 3, 44))                    // still right
        assertEquals(4, resolveRefLine(r, 2, 51))                    // an earlier attach shifted the lines
        assertEquals(3, resolveRefLine(r, 3, 0))                     // old deep link without a page
        assertNull(resolveRefLine(r, 1, 0))                          // already attached
        assertNull(resolveRefLine(r, 1, 40))                         // attached lines lose their page
        assertNull(resolveRefLine(r, 0, 0))                          // not a page reference at all
        assertNull(resolveRefLine(r, 99, 0))
    }
}
