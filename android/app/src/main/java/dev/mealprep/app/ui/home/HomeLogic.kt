package dev.mealprep.app.ui.home

import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.Extra
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.WeekNight
import dev.mealprep.app.ui.cart.ChooserText
import java.time.LocalDate

data class Night(val day: Int, val date: LocalDate, val entries: List<PlanEntry>)
data class WeekView(val week: LocalDate, val nights: List<Night>, val unplaced: List<PlanEntry>, val all: List<PlanEntry>)

fun weekView(week: LocalDate, entries: List<PlanEntry>): WeekView {
    val start = Weeks.weekStart(week)
    return WeekView(
        start,
        (0..6).map { d -> Night(d, Weeks.nightDate(start, d), entries.filter { it.day == d }) },
        entries.filter { it.day == null },
        entries,
    )
}

/** The This-week pager: [pages] in all, the first one [Weeks.PAST] weeks back; [initial] = the page shown first. */
data class PagerSpan(val pages: Int, val initial: Int)

/** [ahead] weeks after this one (Weeks.weeksAhead), widened to a week a link opens [opened] weeks from now (a past
 *  one is clamped to the oldest page). */
fun pagerSpan(ahead: Int, opened: Int): PagerSpan {
    val pages = Weeks.PAST + 1 + maxOf(ahead, opened)
    return PagerSpan(pages, Weeks.PAST + opened.coerceIn(-Weeks.PAST, pages - Weeks.PAST - 1))
}

/** Planned → Cart sent (DESIGN "App: home screen"). */
data class StatusStrip(val planned: Boolean, val cartSent: Boolean)

/** Planned = a dinner on a night, or a night with a name or store items (planned for display; no rating asked). */
fun statusStrip(entries: List<PlanEntry>, carted: Boolean, nights: List<WeekNight> = emptyList()) =
    StatusStrip(entries.any { it.day != null } || nights.any { it.title != null || it.items.isNotEmpty() }, carted)

/** The one context button (docked at the bottom of the week): Build cart → Review cart → Tonight: X (its recipe). */
sealed interface ContextAction {
    val label: String
    /** Nothing in the week: recipes are picked from the library (the Recipes tab). */
    data object AddRecipes : ContextAction { override val label = "Choose from Recipes" }
    data class BuildCart(val week: LocalDate) : ContextAction { override val label get() = "Build cart" }
    data class ReviewCart(val draftId: Int, val building: Boolean) : ContextAction {
        override val label get() = if (building) "Cart building…" else "Review cart"
    }
    data class Tonight(val entryId: Int, val recipeId: Int, val title: String) : ContextAction { override val label get() = "Tonight: $title" }
    data object AllSet : ContextAction { override val label = "✓ All set for this week" }
}

/** [extras]: items added by search for the week or its nights (a week with only store items still builds a cart). */
fun contextAction(week: LocalDate, today: LocalDate, entries: List<PlanEntry>, carted: Boolean, draft: Draft?, extras: Int = 0): ContextAction {
    val start = Weeks.weekStart(week)
    if (entries.isEmpty() && extras == 0) return ContextAction.AddRecipes
    if (start.plusDays(6).isBefore(today)) return ContextAction.AllSet
    val tonight = entries.firstOrNull { it.day != null && Weeks.nightDate(start, it.day) == today }
        ?.let { ContextAction.Tonight(it.id, it.recipeId, it.title ?: "dinner") }
    // After the week's first day, its Monday (the shopping is done by then), tonight's dinner is what matters.
    if (tonight != null && today.isAfter(start)) return tonight
    // A cart built before the week's recipes changed is an older cart: build a new one, don't review that one.
    if (!carted && draft != null && !draft.stale && draft.status in setOf("building", "ready")) return ContextAction.ReviewCart(draft.id, draft.status == "building")
    if (!carted) return ContextAction.BuildCart(start)
    return tonight ?: ContextAction.AllSet
}

/** Ask for the notification permission at most once ever (Android stops showing the prompt after a couple of
 *  refusals anyway, and a nagging app is worse than a missing "recipe added" notice). */
fun shouldAskForNotifications(granted: Boolean, alreadyAsked: Boolean): Boolean = !granted && !alreadyAsked

/** "Use up the fridge · 3 nights free" under the status strip: a week that isn't past, with nights free (from today on,
 *  in this week) and something in the fridge (the saved copy). Null = no row. */
fun useUpFridge(week: LocalDate, today: LocalDate, entries: List<PlanEntry>, fridgeCount: Int): String? {
    val start = Weeks.weekStart(week)
    if (fridgeCount <= 0 || start.plusDays(6).isBefore(today)) return null
    val n = freeNights(start, today, entries)
    if (n <= 0) return null
    return "Use up the fridge · $n night${if (n == 1) "" else "s"} free"
}

/** Nights of the week with no dinner on them, from today on for this week. */
fun freeNights(week: LocalDate, today: LocalDate, entries: List<PlanEntry>): Int {
    val start = Weeks.weekStart(week)
    val taken = entries.mapNotNull { it.day }.toSet()
    return (0..6).count { d -> d !in taken && !Weeks.nightDate(start, d).isBefore(today) }
}

/** "4 Eggs", "4 Eggs and 1 cup Whole milk", "4 Eggs, 2 Lemons and 1 cup Whole milk". */
fun andList(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}

/** The morning-after fridge line: "Update the fridge? Tuesday's Frittata used 4 Eggs and 1 cup Whole milk." */
fun fridgeNudgeText(n: dev.mealprep.app.data.api.FridgeNight): String {
    val day = runCatching { Weeks.longDayLabel(Weeks.dayOf(LocalDate.parse(n.date))) }.getOrDefault("Last night")
    return if (n.uses.isEmpty()) "$day's ${n.title} used what's in the fridge."
        else "Update the fridge? $day's ${n.title} used ${andList(n.uses.map { it.used })}."
}

/** "Fridge updated · Eggs 2, Whole milk 3 cups". */
fun fridgeUpdatedText(a: dev.mealprep.app.data.api.UsedAnswer): String =
    if (a.changes.isEmpty()) "Fridge updated" else "Fridge updated · " + a.changes.joinToString(", ") { it.text }

// --- a night's name and store items (2026-11-06; as the web's useNights / useNightItems) ---

/** The longest night name the server keeps. */
const val NIGHT_TITLE_MAX = 60

/** The name as the server keeps it: spaces collapsed, trimmed; null = no name. */
fun cleanNightTitle(t: String): String? = t.trim().replace(Regex("\\s+"), " ").ifEmpty { null }

/** A night's name in the week's nights: a night with no name and no items drops out. */
fun withTitle(nights: List<WeekNight>, day: Int, title: String?): List<WeekNight> {
    val out = if (nights.any { it.day == day }) nights.map { if (it.day == day) it.copy(title = title) else it }
        else if (title != null) nights + WeekNight(day, title) else nights
    return out.filter { it.title != null || it.items.isNotEmpty() }.sortedBy { it.day }
}

/** A changed or added item on its night (moved off any other; day null = for the week, off the nights). */
fun putItem(nights: List<WeekNight>, e: Extra): List<WeekNight> {
    val rest = nights.map { n -> n.copy(items = n.items.filter { it.id != e.id }) }
    val out = when {
        e.day == null -> rest
        rest.any { it.day == e.day } -> rest.map { n -> if (n.day == e.day) n.copy(items = (n.items + e).sortedBy { it.id }) else n }
        else -> rest + WeekNight(e.day, null, listOf(e))
    }
    return out.filter { it.title != null || it.items.isNotEmpty() }.sortedBy { it.day }
}

fun dropItem(nights: List<WeekNight>, id: Int): List<WeekNight> =
    nights.map { n -> n.copy(items = n.items.filter { it.id != id }) }.filter { it.title != null || it.items.isNotEmpty() }

/** "Hearthstone Pepperoni Pizza" (brand + product), else what was searched. */
fun itemName(e: Extra): String = e.product?.let { p -> listOfNotNull(p.brand, p.name).joinToString(" ") } ?: e.term

/** "×2", or grams for a weighed product; null for a single pack. */
fun itemCount(e: Extra): String? = when {
    e.product?.weighted == true -> ChooserText.quantity(e.quantity, e.product)
    e.quantity > 1 -> "×${e.quantity}"
    else -> null
}

/** "From the store: Hearthstone Pepperoni Pizza ×2 · Caesar Salad Kit". */
fun storeLine(items: List<Extra>): String =
    "From the store: " + items.joinToString(" · ") { e -> listOfNotNull(itemName(e), itemCount(e)).joinToString(" ") }
