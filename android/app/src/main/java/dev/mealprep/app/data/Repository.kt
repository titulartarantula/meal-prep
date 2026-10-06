package dev.mealprep.app.data

import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Bodies
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.DraftIn
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.EntryIn
import dev.mealprep.app.data.api.Health
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.JobStarted
import dev.mealprep.app.data.api.ListIn
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.data.api.MealPrepApi
import dev.mealprep.app.data.api.PendingRating
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.data.api.PrepIn
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.data.api.PrepTask
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.data.api.RatingIn
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.RecipeSource
import dev.mealprep.app.data.api.SearchIn
import dev.mealprep.app.data.api.Staple
import dev.mealprep.app.data.api.StapleIn
import dev.mealprep.app.data.api.ShareIn
import dev.mealprep.app.data.api.ShareResult
import dev.mealprep.app.data.api.TaskDone
import dev.mealprep.app.data.api.WeekSummary
import dev.mealprep.app.data.api.apiCall
import dev.mealprep.app.data.cache.CacheDao
import dev.mealprep.app.data.cache.CachedDoc
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.work.Importer
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import retrofit2.HttpException

fun interface ApiProvider { fun api(): MealPrepApi? }

/** Rebuilds the Retrofit client when the server URL changes; the token is read per request. */
class SettingsApiProvider(private val settings: StateFlow<Settings>) : ApiProvider {
    private val client = Http.client({ settings.value.token })
    private var cached: Pair<String, MealPrepApi>? = null

    @Synchronized override fun api(): MealPrepApi? {
        val s = settings.value
        if (!s.configured) return null
        cached?.let { (url, api) -> if (url == s.serverUrl) return api }
        return runCatching { Http.api(s.serverUrl, client) }.getOrNull()?.also { cached = s.serverUrl to it }
    }
}

/** A read that may come from the saved copy. value == null && error == null means "the server says there is none". */
data class Loaded<T>(val value: T?, val error: ApiError?, val fetchedAt: Instant?) {
    /** Showing a saved copy because the server couldn't be asked. */
    val offline: Boolean get() = error != null && fetchedAt != null
}

class Repository(
    private val apis: ApiProvider,
    private val cache: CacheDao,
    private val now: () -> Instant = Instant::now,
) : Importer {
    private val json = Http.json

    suspend fun <T> call(block: suspend (MealPrepApi) -> T): ApiResult<T> {
        val api = apis.api() ?: return ApiResult.Err(ApiError.NotConfigured)
        return apiCall { block(api) }
    }

    private suspend fun <T> load(key: String, ser: KSerializer<T>, fetch: suspend (MealPrepApi) -> T): Loaded<T> =
        when (val r = call(fetch)) {
            is ApiResult.Ok -> {
                val at = now()
                cache.put(CachedDoc(key, json.encodeToString(ser, r.value), at.toEpochMilli()))
                Loaded(r.value, null, at)
            }
            is ApiResult.Err -> {
                val doc = cache.get(key)
                val saved = doc?.let { runCatching { json.decodeFromString(ser, it.json) }.getOrNull() }
                if (saved == null && doc?.json != "null") Loaded(null, r.error, null)
                else Loaded(saved, r.error, Instant.ofEpochMilli(doc!!.fetchedAt))
            }
        }

    /** Like load, but a 404 means "none" (and is saved as such). */
    private suspend fun <T : Any> loadOptional(key: String, ser: KSerializer<T>, fetch: suspend (MealPrepApi) -> T): Loaded<T?> =
        load(key, ser.nullable) { api ->
            try { fetch(api) } catch (e: HttpException) { if (e.code() == 404) null else throw e }
        }

    private fun wk(d: LocalDate) = Weeks.weekStart(d).toString()

    // --- cached reads (offline copies) ---
    suspend fun weeks(from: LocalDate, count: Int = 8): Loaded<List<WeekSummary>> =
        load("weeks:${wk(from)}:$count", ListSerializer(WeekSummary.serializer())) { it.weeks(wk(from), count) }
    suspend fun week(week: LocalDate): Loaded<List<PlanEntry>> =
        load("week:${wk(week)}", ListSerializer(PlanEntry.serializer())) { it.week(wk(week)) }
    suspend fun recipes(sort: String = "newest"): Loaded<List<Recipe>> =
        load("recipes:$sort", ListSerializer(Recipe.serializer())) { it.recipes(sort) }
    suspend fun recipe(id: Int): Loaded<Recipe> = load("recipe:$id", Recipe.serializer()) { it.recipe(id) }
    suspend fun sources(): Loaded<List<RecipeSource>> = load("sources", ListSerializer(RecipeSource.serializer())) { it.sources() }
    suspend fun weekPrepPlan(week: LocalDate): Loaded<PrepPlan?> =
        loadOptional("prep:${wk(week)}", PrepPlan.serializer()) { it.weekPrepPlan(wk(week)) }
    suspend fun card(entryId: Int): Loaded<CookCard?> = loadOptional("card:$entryId", CookCard.serializer()) { it.card(entryId) }
    suspend fun weekDraft(week: LocalDate): Loaded<Draft?> =
        loadOptional("draft:${wk(week)}", Draft.serializer()) { it.weekDraft(wk(week)) }
    suspend fun staples(): Loaded<List<Staple>> = load("staples", ListSerializer(Staple.serializer())) { it.staples() }
    suspend fun pendingRatings(today: LocalDate): Loaded<List<PendingRating>> =
        load("pending", ListSerializer(PendingRating.serializer())) { it.pendingRatings(today.toString()) }

    // --- live reads ---
    suspend fun health(): ApiResult<Health> = call { it.health() }
    suspend fun defaultCartWeek(): ApiResult<LocalDate?> = call { api -> api.defaultCartWeek().week?.let(LocalDate::parse) }
    suspend fun draft(id: Int): ApiResult<Draft> = call { it.draft(id) }
    suspend fun prepPlan(id: Int): ApiResult<PrepPlan> = call { it.prepPlan(id) }
    suspend fun shoppingList(weeks: List<LocalDate>, staples: List<Int> = emptyList()): ApiResult<List<ListItem>> =
        call { it.list(ListIn(weeks.map(::wk), staples = staples)) }

    // --- writes ---
    /** Sets the recipe's book and page (blank = not known / none). */
    suspend fun editSource(id: Int, title: String?, ref: String?): ApiResult<Recipe> =
        call { it.patchRecipe(id, Bodies.sourcePatch(title.clean(), ref.clean())) }
    suspend fun addToWeek(week: LocalDate, recipeId: Int): ApiResult<PlanEntry> = call { it.addEntry(wk(week), EntryIn(recipeId)) }
    suspend fun placeEntry(entryId: Int, day: Int?): ApiResult<Unit> =
        call { it.patchEntry(entryId, Bodies.entryPatch(day = day, unplace = day == null)) }
    suspend fun scaleEntry(entryId: Int, multiplier: Double): ApiResult<Unit> =
        call { it.patchEntry(entryId, Bodies.entryPatch(multiplier = multiplier)) }
    suspend fun removeEntry(entryId: Int): ApiResult<Unit> = call { it.deleteEntry(entryId) }
    suspend fun setRating(entryId: Int, family: Int, company: String?, note: String?): ApiResult<Unit> =
        call { it.putRating(entryId, RatingIn(family, company, note?.trim()?.ifBlank { null })) }
    suspend fun deleteRating(entryId: Int): ApiResult<Unit> = call { it.deleteRating(entryId) }
    suspend fun createDraft(items: List<ListItem>, weeks: List<LocalDate>): ApiResult<JobStarted> =
        call { it.createDraft(DraftIn(items, weeks.map(::wk))) }
    suspend fun setLineQuantity(draftId: Int, lineId: Int, quantity: Int): ApiResult<DraftLine> =
        call { it.patchLine(draftId, lineId, Bodies.linePatch(quantity = quantity)) }
    suspend fun setLineRemoved(draftId: Int, lineId: Int, removed: Boolean): ApiResult<DraftLine> =
        call { it.patchLine(draftId, lineId, Bodies.linePatch(removed = removed)) }
    suspend fun chooseProduct(draftId: Int, lineId: Int, code: String): ApiResult<DraftLine> =
        call { it.patchLine(draftId, lineId, Bodies.linePatch(productCode = code)) }
    suspend fun searchLine(draftId: Int, lineId: Int, term: String): ApiResult<List<Product>> =
        call { it.searchLine(draftId, lineId, SearchIn(term)) }
    suspend fun sendDraft(draftId: Int): ApiResult<String> = call { it.sendDraft(draftId).pcxCartId }
    suspend fun addStaple(name: String, qty: Double?, unit: String?, weekly: Boolean): ApiResult<Staple> =
        call { it.addStaple(StapleIn(name.trim(), qty, unit?.trim()?.ifBlank { null }, weekly)) }
    suspend fun editStaple(id: Int, name: String, qty: Double?, unit: String?, weekly: Boolean): ApiResult<Staple> =
        call { it.patchStaple(id, Bodies.staplePatch(name = name.trim(), qty = qty, unit = unit?.trim()?.ifBlank { null },
            weekly = weekly, amount = true)) }
    suspend fun setStapleWeekly(id: Int, weekly: Boolean): ApiResult<Staple> =
        call { it.patchStaple(id, Bodies.staplePatch(weekly = weekly)) }
    suspend fun moveStaple(id: Int, position: Int): ApiResult<Staple> =
        call { it.patchStaple(id, Bodies.staplePatch(position = position)) }
    suspend fun deleteStaple(id: Int): ApiResult<Unit> = call { it.deleteStaple(id) }
    suspend fun startPrep(weeks: List<LocalDate>): ApiResult<JobStarted> = call { it.startPrep(PrepIn(weeks.map(::wk))) }
    suspend fun tickTask(planId: Int, taskId: String, done: Boolean): ApiResult<PrepTask> =
        call { it.tickTask(planId, taskId, TaskDone(done)) }

    // --- Importer ---
    override suspend fun shareLink(text: String, week: LocalDate?): ApiResult<ShareResult> =
        call { it.share(ShareIn(text, week?.toString())) }
    override suspend fun importPhotos(pages: List<File>, week: LocalDate?, title: String?, book: String?, page: String?): ApiResult<ShareResult> =
        call { it.photo(Http.pageParts(pages), week?.let { w -> Http.textPart(w.toString()) },
            title?.takeIf(String::isNotBlank)?.let(Http::textPart), book.clean()?.let(Http::textPart), page.clean()?.let(Http::textPart)) }
    override suspend fun attachPages(recipeId: Int, pages: List<File>, forLine: Int): ApiResult<Recipe> =
        call { it.pages(recipeId, Http.pageParts(pages), Http.textPart(forLine.toString())) }

    override suspend fun fetchRecipe(id: Int): ApiResult<Recipe> = call { it.recipe(id) }
    override suspend fun markSending(workId: UUID): Boolean = markOnce("import-sent:$workId")
    override suspend fun clearSending(workId: UUID) = cache.delete("once:import-sent:$workId")

    private fun String?.clean() = this?.trim()?.replace(Regex("""\s+"""), " ")?.ifEmpty { null }

    // --- bookkeeping ---
    /** True the first time a key is seen (notification de-duplication across workers and phones' restarts). */
    suspend fun markOnce(key: String): Boolean {
        if (cache.get("once:$key") != null) return false
        cache.put(CachedDoc("once:$key", "1", now().toEpochMilli()))
        return true
    }

    suspend fun isMarked(key: String): Boolean = cache.get("once:$key") != null

    suspend fun prune(olderThan: Duration) = cache.prune(now().minus(olderThan).toEpochMilli())
}
