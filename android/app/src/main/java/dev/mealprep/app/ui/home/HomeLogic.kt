package dev.mealprep.app.ui.home

import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.PrepPlan
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

/** Planned → Cart sent → Prep done (DESIGN "App: home screen"). */
data class StatusStrip(val planned: Boolean, val cartSent: Boolean, val prepDone: Boolean)

fun prepDone(prep: PrepPlan?): Boolean =
    prep != null && prep.status == "ready" && prep.checklist.done >= prep.checklist.total

fun statusStrip(entries: List<PlanEntry>, carted: Boolean, prep: PrepPlan?) =
    StatusStrip(entries.any { it.day != null }, carted, prepDone(prep))

/** The one context button: Build cart → Start prep → Tonight: X. */
sealed interface ContextAction {
    val label: String
    /** Nothing in the week: recipes are picked from the library (the Recipes tab). */
    data object AddRecipes : ContextAction { override val label = "Add recipes from Recipes" }
    data class BuildCart(val week: LocalDate) : ContextAction { override val label get() = "Build cart" }
    data class ReviewCart(val draftId: Int, val building: Boolean) : ContextAction {
        override val label get() = if (building) "Cart building…" else "Review cart"
    }
    data class StartPrep(val week: LocalDate) : ContextAction { override val label get() = "Start prep" }
    data class ContinuePrep(val week: LocalDate, val done: Int, val total: Int, val building: Boolean) : ContextAction {
        override val label get() = if (building) "Prep plan on its way…" else "Prep: $done of $total done"
    }
    data class Tonight(val entryId: Int, val title: String) : ContextAction { override val label get() = "Tonight: $title" }
    data object AllSet : ContextAction { override val label = "All set" }
}

fun contextAction(week: LocalDate, today: LocalDate, entries: List<PlanEntry>, carted: Boolean, draft: Draft?, prep: PrepPlan?): ContextAction {
    val start = Weeks.weekStart(week)
    if (entries.isEmpty()) return ContextAction.AddRecipes
    if (start.plusDays(6).isBefore(today)) return ContextAction.AllSet
    val tonight = entries.firstOrNull { it.day != null && Weeks.nightDate(start, it.day) == today }
        ?.let { ContextAction.Tonight(it.id, it.title ?: "dinner") }
    // After the prep Sunday (or once prep is done) tonight's dinner is what matters.
    if (tonight != null && (today.isAfter(start) || prepDone(prep))) return tonight
    if (!carted && draft != null && draft.status in setOf("building", "ready")) return ContextAction.ReviewCart(draft.id, draft.status == "building")
    if (!carted) return ContextAction.BuildCart(start)
    if (prep == null || prep.status == "failed") return ContextAction.StartPrep(start)
    if (!prepDone(prep)) return ContextAction.ContinuePrep(start, prep.checklist.done, prep.checklist.total, prep.status == "building")
    return tonight ?: ContextAction.AllSet
}

/** Ask for the notification permission at most once ever (Android stops showing the prompt after a couple of
 *  refusals anyway, and a nagging app is worse than a missing "recipe added" notice). */
fun shouldAskForNotifications(granted: Boolean, alreadyAsked: Boolean): Boolean = !granted && !alreadyAsked
