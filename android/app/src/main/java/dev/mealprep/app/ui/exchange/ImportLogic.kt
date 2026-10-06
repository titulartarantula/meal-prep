package dev.mealprep.app.ui.exchange

import dev.mealprep.app.data.api.ImportItem
import dev.mealprep.app.data.api.ImportReport

/** The preview's sections, in screen order. */
enum class ImportSection(val title: String) {
    NEW("New"), CHANGED("Changed since it was exported"), ALREADY("Already in Recipes"), FAILED("Couldn't read"),
}

/** Plain words for the import screens (pure; tested). */
object ImportLogic {
    fun section(i: ImportItem): ImportSection = when {
        i.status == "failed" -> ImportSection.FAILED
        i.status == "new" -> ImportSection.NEW
        i.canUpdate -> ImportSection.CHANGED
        else -> ImportSection.ALREADY
    }

    /** Can be ticked: a new recipe, one changed since it was exported (Replace), or a same-name match (Add anyway).
     *  A recipe already in the library under the same id, link or title + source can't be added twice. */
    fun selectable(i: ImportItem): Boolean = i.status != "failed" && (i.status == "new" || i.canUpdate || i.canAddAnyway)

    /** What a tick means for the item: replace the library's copy, or add it. */
    fun onAction(i: ImportItem): String = if (i.canUpdate) "update" else "add"

    /** The server's default action: new recipes ticked, everything else not. */
    fun defaultTicks(r: ImportReport): Set<String> = r.items.filter { selectable(it) && it.action != "skip" }.map { it.key }.toSet()

    /** Every item that isn't failed, with the ticked action or skip (the server's contract: send every key). */
    fun choices(r: ImportReport, ticks: Set<String>): Map<String, String> =
        r.items.filter { it.status != "failed" }.associate { it.key to if (it.key in ticks && selectable(it)) onAction(it) else "skip" }

    fun adds(r: ImportReport, ticks: Set<String>) = r.items.count { it.key in ticks && selectable(it) && onAction(it) == "add" }
    fun updates(r: ImportReport, ticks: Set<String>) = r.items.count { it.key in ticks && selectable(it) && onAction(it) == "update" }

    private fun recipes(n: Int) = if (n == 1) "1 recipe" else "$n recipes"

    /** The docked button: "Add 12 recipes" / "Update 1 recipe" / "Add 12, update 1" / "Nothing selected". */
    fun buttonLabel(adds: Int, updates: Int): String = when {
        adds > 0 && updates > 0 -> "Add $adds, update $updates"
        adds > 0 -> "Add ${recipes(adds)}"
        updates > 0 -> "Update ${recipes(updates)}"
        else -> "Nothing selected"
    }

    /** "12 new · 3 already in Recipes · 1 couldn't be read". */
    fun summary(r: ImportReport): String {
        val changed = r.items.count { section(it) == ImportSection.CHANGED }
        val already = r.items.count { section(it) == ImportSection.ALREADY }
        return listOfNotNull(
            "${r.items.count { section(it) == ImportSection.NEW }} new",
            changed.takeIf { it > 0 }?.let { "$it changed since exported" },
            already.takeIf { it > 0 }?.let { "$it already in Recipes" },
            r.count("failed").takeIf { it > 0 }?.let { "$it couldn't be read" },
        ).joinToString(" · ")
    }

    fun title(i: ImportItem) = i.title?.takeIf { it.isNotBlank() } ?: "Recipe ${i.index + 1} (no name)"

    private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"

    /** "New · 9 ingredients · 5 steps · Other · recipes.example.org · 2 ratings": the state in words first. */
    fun detail(i: ImportItem): String = listOfNotNull(
        when (section(i)) {
            ImportSection.NEW -> "New"; ImportSection.CHANGED -> "Changed since it was exported"
            ImportSection.ALREADY -> "Already in Recipes"; ImportSection.FAILED -> "Couldn't read"
        },
        i.ingredients.takeIf { it > 0 }?.let { plural(it, "ingredient") }, i.steps.takeIf { it > 0 }?.let { plural(it, "step") },
        i.source, i.ratings.takeIf { it > 0 }?.let { plural(it, "rating") },
    ).joinToString(" · ")

    /** "Same as “Lentil soup” in Recipes" (or earlier in this file); a name-only match says so. */
    fun matchLine(i: ImportItem): String? {
        val m = i.match ?: return null
        val where = if (m.recipeId == null) "earlier in this file" else "in Recipes"
        return if (m.by == "title") "Same name as “${m.title}” $where" else "Same as “${m.title}” $where"
    }

    /** The tick's words on rows that aren't plain new recipes. */
    fun tickLabel(i: ImportItem): String? = when {
        i.canUpdate -> "Replace with the file's version"
        i.canAddAnyway -> "Add anyway"
        else -> null
    }

    // --- the job ---
    fun running(job: ImportReport) = job.status == "running"

    /** "Adding 3 of 12…" while the job runs. */
    fun progress(job: ImportReport): String =
        if (job.progress.total == 0) "Starting…" else "Adding ${minOf(job.progress.done + 1, job.progress.total)} of ${job.progress.total}…"

    /** "Added 12 recipes. Updated 1. 3 were already in Recipes. 1 couldn't be added." */
    fun result(job: ImportReport): String {
        val added = job.count("added"); val updated = job.count("updated")
        val dup = job.count("duplicate"); val failed = job.count("failed")
        return listOfNotNull(
            if (added > 0) "Added ${recipes(added)}." else if (updated == 0) "No recipes were added." else null,
            updated.takeIf { it > 0 }?.let { "Updated ${recipes(it)}." },
            dup.takeIf { it > 0 }?.let { if (it == 1) "1 was already in Recipes." else "$it were already in Recipes." },
            failed.takeIf { it > 0 }?.let { "$it couldn't be added." },
        ).joinToString(" ")
    }

    /** The job's own error ("interrupted by a server restart") in our words. */
    fun jobError(job: ImportReport): String? = job.error?.let {
        if (it.contains("restart", ignoreCase = true)) "The import stopped when the server restarted. Import the file again: " +
            "recipes already added are skipped."
        else "The import stopped before the end. Import the file again: recipes already added are skipped."
    }

    /** Notification title when the job finished. */
    fun doneTitle(job: ImportReport): String = when {
        job.status == "failed" -> "Import stopped"
        job.count("failed") > 0 -> "Recipes imported, with problems"
        else -> "Recipes imported"
    }
}
