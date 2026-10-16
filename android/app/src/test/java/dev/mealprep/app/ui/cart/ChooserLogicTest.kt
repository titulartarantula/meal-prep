package dev.mealprep.app.ui.cart

import dev.mealprep.app.data.api.Choice
import dev.mealprep.app.data.api.Choices
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.LastBought
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.fixture
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChooserLogicTest {
    private val page = Http.json.decodeFromString(Choices.serializer(), fixture("choices.json"))
    private val toronto = ZoneId.of("America/Toronto")
    private fun c(code: String, group: String = "other") = Choice(Product(code), group = group)
    private val deli = Product("T", "Turkey", price = 4.5, weighted = true, priceQuantity = 100.0, minQty = 100, maxQty = 999, interval = 100)
    private val each = Product("E", "Cheddar", price = 5.0, minQty = 1, maxQty = 24, interval = 1)

    @Test fun `the fixture page decodes with every new field`() {
        assertEquals(6, page.choices.size)
        assertTrue(page.hasMore)
        val cur = page.choices[0]
        assertTrue(cur.current)
        assertEquals("https://img.invalid/21290257_250.png", cur.product.image)
        assertEquals(2, cur.product.images.size)
        assertEquals("21289751_EA", cur.betterValue!!.code)
        assertTrue(page.choices[3].product.sponsored)
        assertTrue(page.choices[5].product.outOfStock)
    }

    @Test fun `pages merge without repeats, in group order, Loblaws order within a group`() {
        val p0 = listOf(c("CUR", "current"), c("A"), c("OUT", "out"), c("D", "deal"))
        val p1 = listOf(c("A"), c("B"), c("D2", "deal"), c("OUT2", "out"))
        assertEquals(listOf("CUR", "D", "D2", "A", "B", "OUT", "OUT2"), ChooserText.merge(p0, p1).map { it.product.code })
    }

    @Test fun `filters keep what matches all of them`() {
        val all = page.choices
        assertEquals(listOf("20975873_EA"), ChooserText.filtered(all, setOf(ChoiceFilter.BOUGHT)).map { it.product.code })
        assertEquals(listOf("21289751_EA"), ChooserText.filtered(all, setOf(ChoiceFilter.SALE)).map { it.product.code })
        assertEquals(listOf("21370317_EA"), ChooserText.filtered(all, setOf(ChoiceFilter.OPTIMUM)).map { it.product.code })
        assertTrue(ChooserText.filtered(all, setOf(ChoiceFilter.BOUGHT, ChoiceFilter.SALE)).isEmpty())
        assertEquals(all, ChooserText.filtered(all, emptySet()))
    }

    @Test fun `labels - deal, optimum, last time, better value, prices`() {
        val (cur, bought, sale, ad) = page.choices
        assertEquals("SAVE $1.12 · ends Oct 21", ChooserText.deal(sale.product))
        assertEquals("On sale · ends Oct 21", ChooserText.deal(Product("x", price = 3.0, saleEnds = "2026-10-21")))
        assertNull(ChooserText.deal(cur.product))
        assertEquals("PC Optimum 4,000 pts · ends Nov 4", ChooserText.optimum(ad.product))
        assertEquals("Last bought Sep 28 · $11.49 · now $0.50 less", ChooserText.lastTime(bought, toronto))
        assertEquals("Last bought Sep 28 · $5.49 · now $0.50 more",
            ChooserText.lastTime(Choice(Product("x"), lastBought = LastBought("2026-09-28T22:04:00+00:00", 5.49), priceChange = 0.5), toronto))
        assertEquals("Last bought Sep 28 · $5.49 · same price now",
            ChooserText.lastTime(Choice(Product("x"), lastBought = LastBought("2026-09-28T22:04:00+00:00", 5.49), priceChange = 0.0), toronto))
        assertNull(ChooserText.lastTime(cur))
        assertEquals("Better value: 400 g at $1.22/100 g", ChooserText.betterValue(cur))
        assertEquals("$4.88 · $1.22/100 g · was $6.00", ChooserText.priceLine(sale.product))
        assertEquals("$4.50/100 g", ChooserText.shelfPrice(deli))
        assertEquals("$1.96/kg", ChooserText.unitPrice(1.96, "1 kg"))
    }

    @Test fun `a late-evening send shows the phone's day`() {
        // 2026-09-29 02:00 UTC is still Sep 28 in Toronto.
        val b = Choice(Product("x"), lastBought = LastBought("2026-09-29T02:00:00+00:00", 5.0))
        assertEquals("Last bought Sep 28 · $5.00", ChooserText.lastTime(b, toronto))
    }

    @Test fun `stepper keeps to the order limits, by the interval`() {
        assertEquals(100, ChooserText.start(1, deli))
        assertEquals(300, ChooserText.start(227, deli))
        assertEquals(900, ChooserText.start(5000, deli))
        assertEquals(400, ChooserText.more(300, deli))
        assertNull(ChooserText.more(900, deli))
        assertNull(ChooserText.fewer(100, deli))
        assertEquals(200, ChooserText.fewer(300, deli))
        assertEquals(24, ChooserText.start(30, each))
        assertNull(ChooserText.more(24, each))
        assertNull(ChooserText.fewer(1, each))
        assertEquals(3, ChooserText.more(2, Product("old")))                      // a cart from before 0.12.0
        assertEquals("100 g less", ChooserText.fewerLabel(deli, "turkey"))
        assertEquals("One more cheddar", ChooserText.moreLabel(each, "cheddar"))
    }

    @Test fun `grams read as g or kg, costs count grams by the 100`() {
        assertEquals("300 g", ChooserText.grams(300))
        assertEquals("1 kg", ChooserText.grams(1000))
        assertEquals("1.25 kg", ChooserText.grams(1250))
        assertEquals("300 g", ChooserText.quantity(300, deli))
        assertEquals("2", ChooserText.quantity(2, each))
        assertEquals(13.5, deli.cost(300)!!, 0.001)
        assertEquals(10.0, each.cost(2)!!, 0.001)
        assertEquals(23.5, estimatedTotal(listOf(DraftLine(1, product = deli, quantity = 300), DraftLine(2, product = each, quantity = 2))), 0.001)
    }

    @Test fun `a cart from before 0_12_0 still decodes - no photo, no pin`() {
        val d = Http.json.decodeFromString(Draft.serializer(), fixture("draft_ready.json"))
        val p = d.lines[0].product!!
        assertNull(p.image)
        assertFalse(p.weighted)
        assertFalse(d.lines[0].pinned)
        assertNull(d.lines[0].note)
        assertEquals(3.99, p.cost(1)!!, 0.001)
    }

    @Test fun `cover text and the Bigger packs filter`() {
        val club = Choice(Product("C", "Lean Ground Beef, Club Pack"), bulk = true, cover = dev.mealprep.app.data.api.Cover(1, 42.46, "≈ 1.75 kg"))
        val tray = Choice(Product("T", "Lean Ground Beef", packageSize = "450 g"), cover = dev.mealprep.app.data.api.Cover(4, 44.0))
        assertEquals("Covers it: 1 × ≈ 1.75 kg · $42.46", ChooserText.cover(club))
        assertEquals("Covers it: 4 × 450 g · $44.00", ChooserText.cover(tray))
        assertEquals("Covers it: 2 packs · $9.00", ChooserText.cover(Choice(Product("X"), cover = dev.mealprep.app.data.api.Cover(2, 9.0))))
        assertNull(ChooserText.cover(c("N")))
        assertEquals(listOf("C"), ChooserText.filtered(listOf(club, tray), setOf(ChoiceFilter.BULK)).map { it.product.code })
    }

    @Test fun `choices from an older server decode without cover or bulk`() {
        val c = Http.json.decodeFromString(Choice.serializer(), """{"product":{"code":"A"},"group":"other"}""")
        assertNull(c.cover)
        assertFalse(c.bulk)
    }
}
