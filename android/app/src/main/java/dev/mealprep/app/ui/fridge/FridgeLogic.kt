package dev.mealprep.app.ui.fridge

import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.FridgeItem
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.ui.cart.qty
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The fridge's texts, pure (FridgeLogicTest). */
object FridgeLogic {
    /** An amount older than this many days shows "last checked N days ago". */
    const val STALE_DAYS = 7
    const val OFFLINE = "Changing the fridge needs the home network (or WireGuard)."
    const val EMPTY = "Add what needs using up: eggs, milk, vegetables, fresh herbs."
    const val MAX_QTY = 10_000.0

    /** The unit chips: label to what is sent (null = a count). Anything else goes in the unit field as typed. */
    val UNITS: List<Pair<String, String?>> = listOf("each" to null, "g" to "g", "kg" to "kg", "ml" to "ml", "L" to "l",
        "cup" to "cup", "bunch" to "bunch", "bag" to "bag", "pack" to "package")

    private val LONG_DATE = DateTimeFormatter.ofPattern("MMMM d", Locale.US)
    private val PLURAL = setOf("cup", "bunch", "bag", "package", "container", "carton", "can", "jar", "bottle", "head",
        "clove", "box", "packet", "stalk", "sprig", "slice", "stick", "piece", "fillet")

    /** "L" for litres; a count unit or cup in the plural past one ("2 bunches"). */
    fun unitText(unit: String?, q: Double): String? = when {
        unit == null -> null
        unit == "l" -> "L"
        q > 1 && unit in PLURAL -> unit + if (unit.endsWith("ch") || unit.endsWith("sh") || unit.endsWith("x")) "es" else "s"
        else -> unit
    }

    /** "6", "2 cups", "½ bag", "about 4" (a photo's estimate); null when the item has no amount ("some"). */
    fun amount(i: FridgeItem): String? = i.qty?.let { q ->
        val a = listOfNotNull(qty(q), unitText(i.unit, q)).joinToString(" ")
        if (i.qtyEstimated) "about $a" else a
    }

    /** The row's first line: "Eggs · 6", "Spinach · about 1 bag", "Yogurt". */
    fun title(i: FridgeItem): String = amount(i)?.let { "${i.name} · $it" } ?: i.name

    fun stale(i: FridgeItem): Boolean = i.daysSinceChecked > STALE_DAYS

    private fun asOfDate(i: FridgeItem): LocalDate? = runCatching { LocalDate.parse(i.amountAsOf) }.getOrNull()

    /** "as of today", "as of yesterday", "as of Oct 6"; "last checked 9 days ago" once older than a week. */
    fun asOf(i: FridgeItem, today: LocalDate): String {
        if (stale(i)) return "last checked ${i.daysSinceChecked} days ago"
        val d = asOfDate(i) ?: return ""
        return when (d) {
            today -> "as of today"
            today.minusDays(1) -> "as of yesterday"
            else -> "as of ${Weeks.shortDate(d)}"
        }
    }

    /** "Use soon", or "use by Oct 25"; null for neither. */
    fun tag(i: FridgeItem): String? = when {
        i.useSoon -> "Use soon"
        i.useBy != null -> runCatching { "use by ${Weeks.shortDate(LocalDate.parse(i.useBy))}" }.getOrNull()
        else -> null
    }

    /** The row's second line: "as of Oct 6 · from a photo · use by Oct 25" (the stale text has its own line). */
    fun details(i: FridgeItem, today: LocalDate): String =
        listOfNotNull(asOf(i, today).takeUnless { stale(i) || it.isEmpty() }, "from a photo".takeIf { i.qtyEstimated && i.qty != null },
            tag(i)).joinToString(" · ")

    /** What TalkBack reads for a row: "Eggs, 6, as of October 6, use soon". */
    fun description(i: FridgeItem, today: LocalDate): String {
        val asOf = if (stale(i)) asOf(i, today) else asOfDate(i)?.let { d ->
            when (d) { today -> "as of today"; today.minusDays(1) -> "as of yesterday"; else -> "as of ${d.format(LONG_DATE)}" }
        }
        val by = i.useBy?.let { runCatching { "use by ${LocalDate.parse(it).format(LONG_DATE)}" }.getOrNull() }
        return listOfNotNull(i.name, amount(i), asOf, "from a photo".takeIf { i.qtyEstimated && i.qty != null },
            "use soon".takeIf { i.useSoon }, by.takeUnless { i.useSoon }).joinToString(", ")
    }

    /** "6", "1,5", "" (no amount) → a number 0–10 000, null for blank; anything else is a sentence. */
    fun parseAmount(text: String): Result<Double?> {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Result.success(null)
        val v = t.toDoubleOrNull()
        return if (v != null && v >= 0 && v <= MAX_QTY) Result.success(v)
            else Result.failure(IllegalArgumentException("The amount must be a number, like 6 or 1.5 (or leave it empty)."))
    }

    /** Off the home network a change can't be saved: say so rather than "can't reach the server". */
    fun writeMessage(e: ApiError): String = when (e) {
        ApiError.Unreachable, ApiError.NotConfigured -> OFFLINE
        else -> e.userMessage()
    }
}

/** The add / edit form, as typed. [id] null = a new item. [fromList] = the name was picked from the suggestions. */
data class FridgeForm(
    val id: Int? = null, val name: String = "", val amount: String = "", val unit: String = "",
    val useSoon: Boolean = false, val useBy: LocalDate? = null, val fromList: Boolean = false,
) {
    companion object {
        fun of(i: FridgeItem) = FridgeForm(i.id, i.name, i.qty?.let(::plain) ?: "", i.unit ?: "", i.useSoon,
            i.useBy?.let { runCatching { LocalDate.parse(it) }.getOrNull() })

        /** A number as it can be typed back: 6.0 → "6", 1.5 → "1.5". */
        fun plain(q: Double): String = String.format(Locale.US, "%.3f", q).trimEnd('0').trimEnd('.')
    }
}
