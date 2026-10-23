package dev.mealprep.app.data

import dev.mealprep.app.core.ExportFile
import dev.mealprep.app.core.ExportFiles
import dev.mealprep.app.core.Sources
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.AddLineIn
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Bodies
import dev.mealprep.app.data.api.Choices
import dev.mealprep.app.data.api.BookHit
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.DraftIn
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.EntryIn
import dev.mealprep.app.data.api.Extra
import dev.mealprep.app.data.api.ExtraIn
import dev.mealprep.app.data.api.ApplyIn
import dev.mealprep.app.data.api.ApplyLine
import dev.mealprep.app.data.api.FridgeIn
import dev.mealprep.app.data.api.FridgeItem
import dev.mealprep.app.data.api.FridgeNight
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.SuggestIn
import dev.mealprep.app.data.api.Suggestion
import dev.mealprep.app.data.api.UsedAnswer
import dev.mealprep.app.data.api.UsedIn
import dev.mealprep.app.data.api.Health
import dev.mealprep.app.data.api.ImportReport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.nullable
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

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

private const val PLAN_WRITE_WAIT = 40_000L   // ms; longer than a write's connect + read timeouts

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

    /** Plan writes (add to a week, place, scale, remove) still on their way to the server. Reads of what they change
     *  wait for them, so a screen opened right after a change (Recipes after Remove from week) never shows the plan
     *  from before it. */
    private val planWrites = MutableStateFlow(0)

    /** Counted from the call itself, before it suspends: a read started right after it waits for it. */
    private suspend fun <T> planWrite(block: suspend (MealPrepApi) -> T): ApiResult<T> {
        planWrites.update { it + 1 }
        try { return call(block) } finally { planWrites.update { it - 1 } }
    }

    /** Waits for [planWrites] (each ends with its own HTTP timeouts; the cap only keeps a screen from waiting forever). */
    private suspend fun afterPlanWrites() { withTimeoutOrNull(PLAN_WRITE_WAIT) { planWrites.first { it == 0 } } }

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
    // The plan reads wait for plan writes in flight (afterPlanWrites).
    suspend fun weeks(from: LocalDate, count: Int = 8): Loaded<List<WeekSummary>> {
        afterPlanWrites()
        return load("weeks:${wk(from)}:$count", ListSerializer(WeekSummary.serializer())) { it.weeks(wk(from), count) }
    }
    suspend fun week(week: LocalDate): Loaded<List<PlanEntry>> {
        afterPlanWrites()
        return load("week:${wk(week)}", ListSerializer(PlanEntry.serializer())) { it.week(wk(week)) }
    }
    suspend fun recipes(sort: String = "newest"): Loaded<List<Recipe>> {
        afterPlanWrites()   // "On the plan: …"
        return load("recipes:$sort", ListSerializer(Recipe.serializer())) { it.recipes(sort) }
    }
    suspend fun recipe(id: Int): Loaded<Recipe> {
        afterPlanWrites()
        return load("recipe:$id", Recipe.serializer()) { it.recipe(id) }
    }
    suspend fun sources(): Loaded<List<RecipeSource>> = load("sources", ListSerializer(RecipeSource.serializer())) { it.sources() }
    suspend fun weekPrepPlan(week: LocalDate): Loaded<PrepPlan?> =
        loadOptional("prep:${wk(week)}", PrepPlan.serializer()) { it.weekPrepPlan(wk(week)) }
    /** One plan by id (the last ready one, shown while a newer plan is writing or after it failed). */
    suspend fun prepPlanCopy(id: Int): Loaded<PrepPlan?> =
        loadOptional("prepplan:$id", PrepPlan.serializer()) { it.prepPlan(id) }
    suspend fun card(entryId: Int): Loaded<CookCard?> = loadOptional("card:$entryId", CookCard.serializer()) { it.card(entryId) }

    /** Saves a ready plan's cook cards for offline reading; cards already saved from this plan aren't asked for
     *  again. Stops at the first failure (off the home network every call would wait for the connect timeout). */
    suspend fun saveCards(plan: PrepPlan) {
        if (plan.status != "ready") return
        for (e in plan.entries.filter { it.hasCard }) {
            val saved = cache.get("card:${e.entryId}")?.let { runCatching { json.decodeFromString(CookCard.serializer(), it.json) }.getOrNull() }
            if (saved?.prepPlanId == plan.id) continue
            if (card(e.entryId).error != null) return
        }
    }
    suspend fun weekDraft(week: LocalDate): Loaded<Draft?> =
        loadOptional("draft:${wk(week)}", Draft.serializer()) { it.weekDraft(wk(week)) }
    suspend fun staples(): Loaded<List<Staple>> = load("staples", ListSerializer(Staple.serializer())) { it.staples() }
    /** The household's fridge list (one for both phones), with the saved copy offline. */
    suspend fun fridge(): Loaded<List<FridgeItem>> = load("fridge", ListSerializer(FridgeItem.serializer())) { it.fridge() }
    /** A read of fridge photos, its last copy kept (the review stays readable away from home). */
    suspend fun fridgeRead(id: Int): Loaded<FridgeRead> = load("fridgeread:$id", FridgeRead.serializer()) { it.fridgeRead(id) }
    suspend fun pendingRatings(today: LocalDate): Loaded<List<PendingRating>> =
        load("pending", ListSerializer(PendingRating.serializer())) { it.pendingRatings(today.toString()) }

    // --- live reads ---
    suspend fun health(): ApiResult<Health> = call { it.health() }
    suspend fun defaultCartWeek(): ApiResult<LocalDate?> = call { api -> api.defaultCartWeek().week?.let(LocalDate::parse) }
    suspend fun draft(id: Int): ApiResult<Draft> = call { it.draft(id) }
    suspend fun prepPlan(id: Int): ApiResult<PrepPlan> = call { it.prepPlan(id) }
    /** Book suggestions for "Which book?" (live, never saved: offline the caller falls back to the household's books). */
    suspend fun searchBooks(q: String, limit: Int = 8): ApiResult<List<BookHit>> = call { it.searchBooks(q, limit) }
    suspend fun shoppingList(weeks: List<LocalDate>, staples: List<Int> = emptyList(), fridge: Boolean = false): ApiResult<List<ListItem>> {
        afterPlanWrites()
        return call { it.list(ListIn(weeks.map(::wk), staples = staples, fridge = fridge)) }
    }
    /** Names the shopping list uses that contain [q] (typing suggestions for a fridge item). Live. */
    suspend fun fridgeNames(q: String): ApiResult<List<String>> = call { it.fridgeNames(q) }
    /** Library dinners that use the fridge ([week]'s recipes are left out and use it first). Live. */
    suspend fun suggest(nights: Int, people: Int, week: LocalDate?, exclude: List<Int> = emptyList()): ApiResult<Suggestion> {
        afterPlanWrites()
        return call { it.suggest(SuggestIn(nights, people, week?.let(::wk), exclude)) }
    }
    /** The newest fridge read still reading or waiting for review (either phone's), or null. */
    suspend fun openFridgeRead(): ApiResult<FridgeRead?> = call { it.openFridgeRead().read }
    /** Last nights' fridge dinners nobody answered ("Update the fridge?"). Live: either phone may answer. */
    suspend fun fridgeUsed(today: LocalDate): ApiResult<List<FridgeNight>> = call { it.fridgeUsed(today.toString()) }

    // --- writes ---
    /** Sets the recipe's book, page, author and ISBN (blank = not known / none), or with [kind] "other" its source's
     *  name and note (blank = just "Other"; no author/ISBN). */
    suspend fun editSource(id: Int, title: String?, ref: String?, author: String? = null, isbn: String? = null,
                           kind: String = Sources.BOOK): ApiResult<Recipe> {
        val book = kind == Sources.BOOK
        return call { it.patchRecipe(id, Bodies.sourcePatch(title.clean(), ref.clean(), author.takeIf { book }.clean(),
            isbn.takeIf { book }.clean(), kind)) }
    }
    /** Edit recipe: [body] holds only the changed fields (RecipeDraft.changes) and the revision the edit began from. */
    suspend fun editRecipe(id: Int, body: kotlinx.serialization.json.JsonObject): ApiResult<Recipe> = call { it.patchRecipe(id, body) }
    suspend fun addToWeek(week: LocalDate, recipeId: Int): ApiResult<PlanEntry> = planWrite { it.addEntry(wk(week), EntryIn(recipeId)) }
    suspend fun placeEntry(entryId: Int, day: Int?): ApiResult<Unit> =
        planWrite { it.patchEntry(entryId, Bodies.entryPatch(day = day, unplace = day == null)) }
    suspend fun scaleEntry(entryId: Int, multiplier: Double): ApiResult<Unit> =
        planWrite { it.patchEntry(entryId, Bodies.entryPatch(multiplier = multiplier)) }
    suspend fun removeEntry(entryId: Int): ApiResult<Unit> = planWrite { it.deleteEntry(entryId) }
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
    /** Product chooser: one page of products for a line; [term] null = the line's item name. Live, never saved. */
    suspend fun choices(draftId: Int, lineId: Int, term: String?, page: Int): ApiResult<Choices> =
        call { it.choices(draftId, lineId, term, page) }
    /** "Use this" in the chooser: the product (when it changes), the quantity and the pin in one PATCH. */
    suspend fun useProduct(draftId: Int, lineId: Int, code: String?, quantity: Int, pinned: Boolean?): ApiResult<DraftLine> =
        call { it.patchLine(draftId, lineId, Bodies.linePatch(productCode = code, quantity = quantity, pinned = pinned)) }
    /** The chooser for an item added by search (no line): one page for [term]. Live, never saved. */
    suspend fun searchChoices(term: String, page: Int): ApiResult<Choices> = call { it.productChoices(term, page) }
    /** "Add an item" on a cart under review: the line (the server also keeps it as an added item of the week). */
    suspend fun addLine(draftId: Int, term: String, code: String, quantity: Int): ApiResult<DraftLine> =
        call { it.addLine(draftId, AddLineIn(term.trim(), code, quantity)) }
    /** Items added by search for [weeks] (live: they change from either phone). */
    suspend fun extras(weeks: List<LocalDate>): ApiResult<List<Extra>> = call { it.extras(weeks.map(::wk)) }
    suspend fun addExtra(week: LocalDate, term: String, code: String, quantity: Int): ApiResult<Extra> =
        call { it.addExtra(ExtraIn(wk(week), term.trim(), code, quantity)) }
    suspend fun setExtraQuantity(id: Int, quantity: Int): ApiResult<Extra> =
        call { it.patchExtra(id, Bodies.extraPatch(quantity = quantity)) }
    suspend fun deleteExtra(id: Int): ApiResult<Unit> = call { it.deleteExtra(id) }
    suspend fun setPinned(draftId: Int, lineId: Int, pinned: Boolean): ApiResult<DraftLine> =
        call { it.patchLine(draftId, lineId, Bodies.linePatch(pinned = pinned)) }
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
    suspend fun addFridge(name: String, qty: Double?, unit: String?, useSoon: Boolean = false, useBy: LocalDate? = null,
                          fromList: Boolean = false): ApiResult<FridgeItem> =
        call { it.addFridge(FridgeIn(name.clean() ?: name, qty, unit.clean(), useSoon, useBy?.toString(), fromList)) }
    /** The edit form saved: every field (null clears qty, unit and use-by); a new name is matched again on the server. */
    suspend fun editFridge(id: Int, name: String, qty: Double?, unit: String?, useSoon: Boolean, useBy: LocalDate?,
                           fromList: Boolean = false): ApiResult<FridgeItem> =
        call { it.patchFridge(id, Bodies.fridgePatch(name.clean() ?: name, qty, unit.clean(), useSoon, useBy?.toString(), fromList)) }
    /** "Still right": the amount is dated today. */
    suspend fun confirmFridge(id: Int): ApiResult<FridgeItem> = call { it.patchFridge(id, Bodies.fridgeConfirm()) }
    suspend fun deleteFridge(id: Int): ApiResult<Unit> = call { it.deleteFridge(id) }
    /** Fridge photos up for a background read (202): the same photos again return the same read. */
    suspend fun uploadFridgePhotos(photos: List<File>): ApiResult<FridgeRead> = call { it.fridgePhotos(Http.pageParts(photos)) }
    suspend fun applyRead(id: Int, lines: List<ApplyLine>): ApiResult<FridgeRead> = call { it.applyRead(id, ApplyIn(lines)) }
    suspend fun dismissRead(id: Int): ApiResult<FridgeRead> = call { it.dismissRead(id) }
    /** "Update fridge" ([apply]) or "Not now" on the morning-after nudge; the first answer from either phone wins. */
    suspend fun answerUsed(entryId: Int, apply: Boolean): ApiResult<UsedAnswer> = call { it.answerUsed(entryId, UsedIn(apply)) }
    suspend fun startPrep(weeks: List<LocalDate>): ApiResult<JobStarted> = call { it.startPrep(PrepIn(weeks.map(::wk))) }
    suspend fun tickTask(planId: Int, taskId: String, done: Boolean): ApiResult<PrepTask> =
        call { it.tickTask(planId, taskId, TaskDone(done)) }

    // --- recipe files (import / export) ---
    /** One recipe as a schema.org file, saved in [dir] under the server's file name (ratings and notes included). */
    suspend fun exportRecipe(id: Int, dir: File): ApiResult<ExportFile> =
        download(dir, "recipe-$id.recipe.json") { it.exportRecipe(id) }
    /** The whole library as one file (a backup other recipe apps can read too). */
    suspend fun exportLibrary(dir: File): ApiResult<ExportFile> =
        download(dir, "meal-prep-recipes-${now().atZone(java.time.ZoneId.systemDefault()).toLocalDate()}.json") { it.exportLibrary() }

    private suspend fun download(dir: File, fallback: String, get: suspend (MealPrepApi) -> Response<ResponseBody>): ApiResult<ExportFile> =
        call { api ->
            val r = get(api)
            if (!r.isSuccessful) throw HttpException(r)
            val name = ExportFiles.fileName(r.headers()["Content-Disposition"]) ?: fallback
            val body = r.body() ?: throw HttpException(r)
            withContext(Dispatchers.IO) { ExportFile(name, ExportFiles.save(body.byteStream(), dir, name, now().toEpochMilli())) }
        }

    /** What importing [file] would do (nothing is written), or, for a document read the first time, its read job
     *  (202: poll [importJob] until done; its report is then the preview). */
    suspend fun importPreview(file: File, name: String? = null): ApiResult<ImportReport> =
        call { it.importRecipes(Http.filePart(file, file.name), Http.textPart("true"), name = name.clean()?.let(Http::textPart)) }
    /** Starts the import with [choices] (item key → add / skip / update): the job (202). Sending the same file and
     *  choices again within minutes returns the same job (existing = true), so a retry never imports twice. */
    suspend fun importApply(file: File, choices: Map<String, String>, name: String? = null): ApiResult<ImportReport> = call {
        it.importRecipes(Http.filePart(file, file.name), Http.textPart("false"),
            Http.textPart(json.encodeToString(MapSerializer(String.serializer(), String.serializer()), choices)),
            name.clean()?.let(Http::textPart))
    }
    /** An import job, with its last copy kept for offline (the result stays readable away from home). */
    suspend fun importJob(id: Int): Loaded<ImportReport> = load("import:$id", ImportReport.serializer()) { it.importJob(id) }

    // --- Importer ---
    override suspend fun shareLink(text: String, week: LocalDate?): ApiResult<ShareResult> =
        call { it.share(ShareIn(text, week?.toString())) }
    override suspend fun importPhotos(pages: List<File>, week: LocalDate?, title: String?, book: String?, page: String?,
                                      author: String?, isbn: String?, kind: String?): ApiResult<ShareResult> {
        val other = kind == Sources.OTHER   // [book] = the other source's name, [page] = its note
        val b = book.clean()   // an author/ISBN only comes with a book
        return call { it.photo(Http.pageParts(pages), week?.let { w -> Http.textPart(w.toString()) },
            title?.takeIf(String::isNotBlank)?.let(Http::textPart), b?.let(Http::textPart), page.clean()?.let(Http::textPart),
            b?.takeUnless { other }?.let { author.clean() }?.let(Http::textPart), b?.takeUnless { other }?.let { isbn.clean() }?.let(Http::textPart),
            if (other) Http.textPart(Sources.OTHER) else null) }
    }
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

    /** This phone's own small records (not from the server: planned reminders, "Later" on a rating). Pruned like
     *  saved copies once older than [prune]'s limit. */
    suspend fun <T> putLocal(key: String, ser: KSerializer<T>, value: T) =
        cache.put(CachedDoc("local:$key", json.encodeToString(ser, value), now().toEpochMilli()))
    suspend fun <T> getLocal(key: String, ser: KSerializer<T>): T? =
        cache.get("local:$key")?.let { runCatching { json.decodeFromString(ser, it.json) }.getOrNull() }

    suspend fun prune(olderThan: Duration) = cache.prune(now().minus(olderThan).toEpochMilli())
}
