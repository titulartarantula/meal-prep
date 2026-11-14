package dev.mealprep.app.ui.home

import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.Extra
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.data.api.WeekNight
import dev.mealprep.app.ui.cart.extraDetail
import dev.mealprep.app.ui.cart.lineDetail
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A night's name and store items (2026-11-07): the cached nights after each write, labels, planned, next step. */
class NightLogicTest {
    private val wk = LocalDate.parse("2026-11-16")
    private fun item(id: Int, day: Int?, qty: Int = 1, name: String = "Thing $id", brand: String? = null, weighted: Boolean = false) =
        Extra(id, "2026-11-16", name.lowercase(), product = Product("P$id", name, brand, weighted = weighted, interval = if (weighted) 100 else null),
            quantity = qty, day = day)

    @Test fun `a name is added, renamed and cleared, and a night with nothing left drops out`() {
        var ns = withTitle(emptyList(), 4, "Pizza night")
        assertEquals(listOf(WeekNight(4, "Pizza night")), ns)
        ns = withTitle(ns, 1, "Leftovers")
        assertEquals(listOf(1, 4), ns.map { it.day })
        ns = withTitle(ns, 4, "Movie night")
        assertEquals("Movie night", ns.first { it.day == 4 }.title)
        assertEquals(listOf(1), withTitle(ns, 4, null).map { it.day })
        // a night with store items stays without its name
        assertEquals(listOf(WeekNight(2, null, listOf(item(1, 2)))), withTitle(listOf(WeekNight(2, "Tacos", listOf(item(1, 2)))), 2, null))
    }

    @Test fun `names are cleaned like the server does`() {
        assertEquals("Pizza night", cleanNightTitle("  Pizza \n  night "))
        assertNull(cleanNightTitle("   "))
    }

    @Test fun `items are put on their night, moved, changed and removed`() {
        var ns = putItem(listOf(WeekNight(1, "Leftovers")), item(5, 4, 2))
        assertEquals(listOf(1 to emptyList(), 4 to listOf(5)), ns.map { it.day to it.items.map { i -> i.id } })
        ns = putItem(ns, item(6, 4))
        ns = putItem(ns, item(5, 6, 2))                                       // moved to Sunday
        assertEquals(listOf(1 to emptyList(), 4 to listOf(6), 6 to listOf(5)), ns.map { it.day to it.items.map { i -> i.id } })
        ns = putItem(ns, item(6, 4, 3))                                       // one more
        assertEquals(3, ns.first { it.day == 4 }.items.single().quantity)
        ns = putItem(ns, item(6, null, 3))                                    // the whole week: off the nights
        assertEquals(listOf(1, 6), ns.map { it.day })
        assertEquals(listOf(WeekNight(1, "Leftovers")), dropItem(ns, 5))
    }

    @Test fun `item labels`() {
        val pizza = item(1, 4, 2, "Pepperoni Pizza", brand = "Hearthstone")
        assertEquals("Hearthstone Pepperoni Pizza", itemName(pizza))
        assertEquals("×2", itemCount(pizza))
        assertNull(itemCount(item(2, 4)))
        assertEquals("300 g", itemCount(item(3, 4, 300, "Ham", weighted = true)))
        assertEquals("From the store: Hearthstone Pepperoni Pizza ×2 · Caesar Kit", storeLine(listOf(pizza, item(4, 4, 1, "Caesar Kit"))))
        assertEquals("cheddar", itemName(Extra(9, "2026-11-16", "cheddar")))
    }

    @Test fun `a night with only a name or store items counts as planned, and store items build a cart`() {
        assertEquals(StatusStrip(false, false), statusStrip(emptyList(), false))
        assertEquals(StatusStrip(true, false), statusStrip(emptyList(), false, listOf(WeekNight(4, "Pizza night"))))
        assertEquals(StatusStrip(true, false), statusStrip(emptyList(), false, listOf(WeekNight(4, null, listOf(item(1, 4))))))
        val today = LocalDate.parse("2026-11-13")
        assertEquals(ContextAction.AddRecipes, contextAction(wk, today, emptyList(), false, null))
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, today, emptyList(), false, null, extras = 2))
        val chili = PlanEntry(1, "2026-11-16", 5, 2, title = "Chili")
        assertEquals(ContextAction.BuildCart(wk), contextAction(wk, today, listOf(chili), false, null, extras = 0))
    }

    @Test fun `the list and the cart say which night an added item is for`() {
        val e = item(1, 4, 2, "Pepperoni Pizza", brand = "Hearthstone")
        assertEquals("2 · for Friday", extraDetail(e))
        assertEquals("2", extraDetail(e.copy(day = null)))
        val line = DraftLine(7, name = "frozen pizza", recipes = listOf("Added"), extraId = 1, day = 4)
        assertEquals("frozen pizza · Added for Fri", lineDetail(line))
        assertEquals("frozen pizza · Added", lineDetail(line.copy(day = null)))
    }
}
