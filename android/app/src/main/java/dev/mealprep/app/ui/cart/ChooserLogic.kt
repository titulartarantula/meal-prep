package dev.mealprep.app.ui.cart

import dev.mealprep.app.data.api.Choice
import dev.mealprep.app.data.api.Product
import java.text.NumberFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** The chooser's filter chips; several on = all of them must hold. */
enum class ChoiceFilter(val label: String) {
    BOUGHT("Bought before"), SALE("On sale"), OPTIMUM("Optimum"), BULK("Bigger packs");

    fun matches(c: Choice): Boolean = when (this) {
        BOUGHT -> c.lastBought != null
        SALE -> c.product.onSale
        OPTIMUM -> c.product.optimumText != null
        BULK -> c.bulk
    }
}

/** On sale: a sale price with an end date, a deal badge ("SAVE $0.99"), or a price under the "was" price. */
val Product.onSale: Boolean get() =
    saleEnds != null || dealText != null || (wasPrice != null && price != null && price < wasPrice)

/** Texts and rules of the product chooser (pure, so the screen and the tests share them). */
object ChooserText {
    private val GROUPS = listOf("current", "bought", "deal", "other", "out")
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val POINTS = NumberFormat.getIntegerInstance(Locale.US)

    /** Pages so far + a new page: each product once (the first time it came), in the server's group order
     *  (current, bought before, deals, the rest, out of stock), Loblaws' order kept within a group. */
    fun merge(old: List<Choice>, new: List<Choice>): List<Choice> {
        val seen = HashSet<String>()
        val all = (old + new).filter { seen.add(it.product.code) }
        return all.withIndex().sortedWith(compareBy({ GROUPS.indexOf(it.value.group).let { g -> if (g < 0) 3 else g } }, { it.index }))
            .map { it.value }
    }

    fun filtered(all: List<Choice>, filters: Set<ChoiceFilter>): List<Choice> =
        if (filters.isEmpty()) all else all.filter { c -> filters.all { it.matches(c) } }

    fun day(iso: String?): String? = iso?.let {
        runCatching { LocalDate.parse(it.take(10)) }.getOrNull()?.format(DAY)
    }

    /** A sent-at timestamp as the phone's local day ("Sep 28"). */
    fun localDay(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String? = iso?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate().format(DAY) }.getOrNull() ?: day(it)
    }

    /** "$1.22/100 g", "$1.96/kg". */
    fun unitPrice(value: Double?, unit: String?): String? {
        if (value == null || unit.isNullOrBlank()) return null
        return "${price(value)}/${unit.removePrefix("1 ")}"
    }

    /** The shelf price: "$4.88", or "$4.50/100 g" for a product sold by weight. */
    fun shelfPrice(p: Product): String? = p.price?.let { v ->
        if (p.weighted && p.priceQuantity != null) "${price(v)}/${grams(p.priceQuantity.toInt())}" else price(v)
    }

    /** Price line of a card: "$4.88 · $1.22/100 g · was $6.00". */
    fun priceLine(p: Product): String = listOfNotNull(shelfPrice(p), unitPrice(p.unitPrice, p.unitPriceUnit),
        p.wasPrice?.takeIf { w -> p.price != null && w > p.price }?.let { "was ${price(it)}" }).joinToString(" · ")

    /** "SAVE $0.99 · ends Oct 21", "On sale · ends Oct 21"; null when not on sale. */
    fun deal(p: Product): String? {
        val ends = day(p.dealEnds ?: p.saleEnds)?.let { "ends $it" }
        return when {
            p.dealText != null -> listOfNotNull(p.dealText, ends).joinToString(" · ")
            p.onSale -> listOfNotNull("On sale", ends).joinToString(" · ")
            else -> null
        }
    }

    /** "PC Optimum 4,000 pts · ends Nov 4"; null without an offer. */
    fun optimum(p: Product): String? {
        if (p.optimumText == null) return null
        val head = p.optimumPoints?.let { "PC Optimum ${POINTS.format(it)} pts" } ?: "PC Optimum offer"
        return listOfNotNull(head, day(p.optimumEnds)?.let { "ends $it" }).joinToString(" · ")
    }

    /** "Last bought Sep 28 · $5.49 · now $0.50 less" ("· same price now" when unchanged). */
    fun lastTime(c: Choice, zone: ZoneId = ZoneId.systemDefault()): String? {
        val b = c.lastBought ?: return null
        val change = c.priceChange?.let { d ->
            when {
                abs(d) < 0.005 -> "same price now"
                d > 0 -> "now ${price(d)} more"
                else -> "now ${price(-d)} less"
            }
        }
        return listOfNotNull("Last bought" + (localDay(b.date, zone)?.let { " $it" } ?: ""), b.price?.let(::price), change)
            .joinToString(" · ")
    }

    /** "Better value: 1 kg at $1.22/100 g". */
    fun betterValue(c: Choice): String? {
        val b = c.betterValue ?: return null
        val what = b.packageSize ?: b.name
        val at = unitPrice(b.unitPrice, b.unitPriceUnit)
        return "Better value: $what" + (at?.let { " at $it" } ?: "")
    }

    /** "Covers it: 1 × ≈ 1.75 kg · $42.46", "Covers it: 4 × 450 g · $44.00"; null without a need to compare. */
    fun cover(c: Choice): String? {
        val v = c.cover ?: return null
        val size = v.size.ifBlank { c.product.packageSize ?: "" }
        val packs = if (size.isBlank()) "${v.packs} ${if (v.packs == 1) "pack" else "packs"}" else "${v.packs} × $size"
        return "Covers it: $packs · ${price(v.cost)}"
    }

    /** Grams as "300 g" or "1.2 kg". */
    fun grams(g: Int): String =
        if (g >= 1000) "${String.format(Locale.US, "%.2f", g / 1000.0).trimEnd('0').trimEnd('.')} kg" else "$g g"

    /** A line's quantity: packs ("2"), or weight ("300 g") for a product sold by weight. */
    fun quantity(q: Int, p: Product?): String = if (p?.weighted == true) grams(q) else "$q"

    private fun step(p: Product?): Int = (p?.interval ?: 1).coerceAtLeast(1)
    private fun min(p: Product?): Int = (p?.minQty ?: 1).coerceAtLeast(1)
    private fun max(p: Product?): Int? = p?.maxQty?.takeIf { it > 0 }

    /** The quantity the stepper starts at: the suggestion, within the product's limits. */
    fun start(q: Int, p: Product?): Int = fit(q, p)

    fun fit(q: Int, p: Product?): Int {
        val s = step(p)
        var v = maxOf(q, min(p))
        if (s > 1 && v % s != 0) v += s - v % s
        max(p)?.let { m -> if (v > m) v = m - if (s > 1) m % s else 0 }
        return v
    }

    fun more(q: Int, p: Product?): Int? = (q + step(p)).takeIf { n -> max(p)?.let { n <= it } ?: true }
    fun fewer(q: Int, p: Product?): Int? = (q - step(p)).takeIf { it >= min(p) }

    /** TalkBack labels of the −/+ buttons: "One fewer cheddar", or "100 g less" by weight. */
    fun fewerLabel(p: Product?, name: String): String = if (p?.weighted == true) "${grams(step(p))} less" else "One fewer $name"
    fun moreLabel(p: Product?, name: String): String = if (p?.weighted == true) "${grams(step(p))} more" else "One more $name"
}
