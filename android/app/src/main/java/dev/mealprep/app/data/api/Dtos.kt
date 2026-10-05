package dev.mealprep.app.data.api

import kotlinx.serialization.Serializable

// Field names are camelCase here; Http.json maps them to the server's snake_case.

@Serializable data class Health(val ok: Boolean, val provider: String? = null)

@Serializable data class Ingredient(
    val raw: String, val name: String, val qty: Double? = null, val unit: String? = null, val prep: String? = null,
    val likelyOnHand: Boolean = false, val refPage: Int? = null, val subRecipe: String? = null, val expanded: Boolean = false,
)

@Serializable data class Rating(val family: Int, val company: String? = null, val note: String? = null, val ratedAt: String? = null)
@Serializable data class RatingNote(val note: String, val date: String? = null, val ratedAt: String? = null)
@Serializable data class RatingSummary(
    val timesCooked: Int = 0, val timesRated: Int = 0, val avgFamily: Double? = null, val lastFamily: Int? = null,
    val lastRatedAt: String? = null, val company: String? = null, val notes: List<RatingNote> = emptyList(),
)
@Serializable data class CookedEntry(
    val entryId: Int, val week: String, val day: Int? = null, val date: String? = null,
    val multiplier: Double = 1.0, val rating: Rating? = null,
)

@Serializable data class Recipe(
    val id: Int, val title: String, val source: String, val sourceUrl: String? = null, val servings: Int? = null,
    val ingredients: List<Ingredient> = emptyList(), val steps: List<String> = emptyList(),
    val ratings: RatingSummary = RatingSummary(), val history: List<CookedEntry> = emptyList(),
) {
    /** Indexes of lines like "Batter for 24 crêpes, page 191" whose page hasn't been attached yet. */
    val missingPages: List<Int> get() = ingredients.indices.filter { ingredients[it].refPage != null && !ingredients[it].expanded }
}

@Serializable data class PlanEntry(
    val id: Int, val week: String, val recipeId: Int, val day: Int? = null, val multiplier: Double = 1.0,
    val rating: Rating? = null, val title: String? = null,
)

@Serializable data class ShareIn(val text: String, val week: String? = null)
@Serializable data class ShareResult(val recipe: Recipe, val entry: PlanEntry, val existing: Boolean = false)
@Serializable data class WeekSummary(val week: String, val entries: Int = 0, val carted: Boolean = false)
@Serializable data class EntryIn(val recipeId: Int)
@Serializable data class RatingIn(val family: Int, val company: String? = null, val note: String? = null)
@Serializable data class PendingRating(
    val entryId: Int, val week: String, val day: Int, val date: String, val recipeId: Int, val title: String,
    val multiplier: Double = 1.0,
)

@Serializable data class ListIn(val weeks: List<String>, val people: Int = 4)
@Serializable data class ListItem(
    val key: String, val name: String, val qty: Double? = null, val unit: String? = null, val prep: String? = null,
    val likelyOnHand: Boolean = false, val needed: Boolean = true, val recipes: List<String> = emptyList(),
)
@Serializable data class DefaultWeek(val week: String? = null)

@Serializable data class Product(
    val code: String, val name: String = "", val brand: String? = null, val packageSize: String? = null,
    val price: Double? = null, val stock: String? = null,
)
@Serializable data class DraftLine(
    val id: Int, val itemKey: String = "", val name: String = "", val qty: Double? = null, val unit: String? = null,
    val prep: String? = null, val recipes: List<String> = emptyList(), val product: Product? = null,
    // Null for unmatched/pending lines (the server sends quantity: null); must not be coerced to 1.
    val quantity: Int? = null, val source: String = "none", val alternatives: List<Product> = emptyList(),
    val removed: Boolean = false, val status: String = "",
    // Purchase planner (absent from older servers): fewest packs that cover the need, whether the server
    // couldn't confirm that, and its one-line explanation ("Need ⅚ cup → 1 × 1 L").
    val packsMin: Int? = null, val needsCheck: Boolean = false, val why: String? = null,
)
@Serializable data class Progress(val done: Int = 0, val total: Int = 0)
@Serializable data class Draft(
    val id: Int, val status: String, val error: String? = null, val progress: Progress = Progress(),
    val weeks: List<String> = emptyList(), val lines: List<DraftLine> = emptyList(), val pcxCartId: String? = null,
    val estimatedTotal: Double = 0.0, val createdAt: String? = null, val sentAt: String? = null,
)
@Serializable data class DraftIn(val items: List<ListItem>, val weeks: List<String>)
@Serializable data class SearchIn(val term: String)
@Serializable data class SentCart(val pcxCartId: String)
@Serializable data class JobStarted(val id: Int, val status: String)

@Serializable data class PrepIn(val weeks: List<String>)
@Serializable data class Serves(val entryId: Int, val recipeId: Int, val title: String, val night: String, val date: String? = null)
@Serializable data class PrepTask(
    val id: String, val section: String, val text: String, val serves: List<Serves> = emptyList(),
    val estMinutes: Int = 0, val shelfLife: String = "ok", val thaw: String? = null,
    val contents: List<String> = emptyList(), val flags: List<String> = emptyList(),
    val done: Boolean = false, val doneAt: String? = null,
)
@Serializable data class PrepSection(val key: String, val title: String, val tasks: List<PrepTask> = emptyList())
@Serializable data class PrepEntry(
    val entryId: Int, val recipeId: Int, val title: String, val night: String, val date: String? = null,
    val hasCard: Boolean = false,
)
@Serializable data class Checklist(
    val done: Int = 0, val total: Int = 0, val estMinutes: Int = 0, val firstDoneAt: String? = null,
    val lastDoneAt: String? = null, val actualMinutes: Int? = null,
)
@Serializable data class PrepPlan(
    val id: Int, val status: String, val error: String? = null, val weeks: List<String> = emptyList(),
    val progress: Progress = Progress(), val createdAt: String? = null, val finishedAt: String? = null,
    val generationSeconds: Double? = null, val totalMinutes: Int? = null, val warnings: List<String> = emptyList(),
    val sections: List<PrepSection> = emptyList(), val entries: List<PrepEntry> = emptyList(),
    val checklist: Checklist = Checklist(), val stale: Boolean = false, val lastReadyId: Int? = null,
)
@Serializable data class TaskDone(val done: Boolean)

@Serializable data class CardStep(val text: String, val minutes: Int? = null, val timerMinutes: Int? = null)
@Serializable data class CookCard(
    val entryId: Int, val recipeId: Int, val title: String, val night: String, val date: String? = null,
    val kit: List<String> = emptyList(), val dayOf: List<String> = emptyList(), val thaw: List<String> = emptyList(),
    val steps: List<CardStep> = emptyList(), val totalMinutes: Int = 0, val ratingNotes: List<String> = emptyList(),
    val prepPlanId: Int? = null, val generatedAt: String? = null, val stale: Boolean = false,
)
