package dev.mealprep.app.ui.fridge

import dev.mealprep.app.data.api.ApplyLine
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.Proposal
import dev.mealprep.app.ui.cart.qty
import kotlinx.serialization.Serializable

/** The reviewer's change to one line: an edited name (new items), amount or unit. An edited number is typed, no
 *  longer the photo's estimate. */
@Serializable data class LineEdit(val name: String? = null, val qty: Double? = null, val unit: String? = null, val amountEdited: Boolean = false)

/** The photo review's texts and the apply body, pure (FridgeReviewLogicTest). */
object ReviewLogic {
    /** Sections in order: (kind, heading). */
    val SECTIONS = listOf("new" to "New", "amount" to "Amounts", "seen" to "Still there", "not_seen" to "Not seen — remove?")

    fun ticks(r: FridgeRead): Set<String> = r.proposals.filter { it.defaultOn }.map { it.pid }.toSet()

    private fun amount(q: Double?, unit: String?): String? =
        q?.let { listOfNotNull(qty(it), FridgeLogic.unitText(unit, it)).joinToString(" ") }

    /** "about 4" for an estimate, "5" for an edited number. */
    private fun shown(p: Proposal, e: LineEdit?): String? =
        if (e?.amountEdited == true) amount(e.qty, e.unit) else amount(p.estimate?.qty, p.estimate?.unit)?.let { "about $it" }

    /** The line in words, state first: "Add · Spinach · about 1 bag (estimate) · crisper drawer", "Change Eggs: 6 → about 4
     *  (estimate)", "Eggs — still there (as of today)", "Remove Cilantro? Not seen in the photos"; low confidence adds "not sure". */
    fun text(p: Proposal, e: LineEdit? = null): String {
        val est = e?.amountEdited != true && p.estimate?.qty != null
        val unsure = " · not sure".takeIf { p.confidence == "low" }.orEmpty()
        return when (p.kind) {
            "new" -> listOfNotNull("Add", e?.name ?: p.name, shown(p, e)?.let { if (est) "$it (estimate)" else it }, p.where)
                .joinToString(" · ") + unsure
            "amount" -> "Change ${p.name}: ${amount(p.current?.qty, p.current?.unit) ?: "some"} → ${shown(p, e) ?: "some"}" +
                (if (est) " (estimate)" else "") + unsure
            "seen" -> if (e?.amountEdited == true) "Change ${p.name}: ${amount(p.current?.qty, p.current?.unit) ?: "some"} → ${shown(p, e) ?: "some"}"
                else "${p.name} — still there (as of today)" + unsure
            else -> "Remove ${p.name}? Not seen in the photos"
        }
    }

    /** The apply body: every line with its decision; name/qty/unit only where the reviewer edited them. */
    fun lines(r: FridgeRead, ticks: Set<String>, edits: Map<String, LineEdit>): List<ApplyLine> = r.proposals.map { p ->
        val e = edits[p.pid]?.takeIf { p.pid in ticks && p.kind != "not_seen" }
        ApplyLine(p.pid, p.pid in ticks, name = e?.name?.takeIf { p.kind == "new" && it != p.name },
            qty = e?.takeIf { it.amountEdited }?.qty, unit = e?.takeIf { it.amountEdited }?.unit)
    }

    /** "Save 4 changes" / "Save 1 change"; the button is off with none ticked. */
    fun saveLabel(n: Int): String = if (n == 1) "Save 1 change" else "Save $n changes"

    /** "Review 5 changes" for the notification. */
    fun notificationText(r: FridgeRead): String {
        val n = r.proposals.size
        return if (n == 0) "Nothing to change." else "Review $n change${if (n == 1) "" else "s"}."
    }

    /** "Saved: 1 added, 2 changed, 1 removed." from the apply's result. */
    fun resultText(result: Map<String, Int>?): String {
        val r = result ?: return "Saved."
        val parts = listOfNotNull(r["added"]?.takeIf { it > 0 }?.let { "$it added" }, r["changed"]?.takeIf { it > 0 }?.let { "$it changed" },
            r["seen"]?.takeIf { it > 0 }?.let { "$it still there" }, r["removed"]?.takeIf { it > 0 }?.let { "$it removed" })
        return if (parts.isEmpty()) "Saved." else "Saved: ${parts.joinToString(", ")}."
    }
}
