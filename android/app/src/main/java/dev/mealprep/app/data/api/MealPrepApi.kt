package dev.mealprep.app.data.api

import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/** The Stage 1/2 server (server/deploy/README.md). Dates are ISO strings ("2026-10-11"). */
interface MealPrepApi {
    @GET("health") suspend fun health(): Health

    @POST("recipes/share") suspend fun share(@Body body: ShareIn): ShareResult
    @Multipart @POST("recipes/photo")
    suspend fun photo(@Part files: List<MultipartBody.Part>, @Part("week") week: RequestBody?, @Part("title") title: RequestBody?): ShareResult
    @Multipart @POST("recipes/{id}/pages")
    suspend fun pages(@Path("id") id: Int, @Part files: List<MultipartBody.Part>, @Part("for_line") forLine: RequestBody?): Recipe
    @GET("recipes") suspend fun recipes(@Query("sort") sort: String): List<Recipe>
    @GET("recipes/{id}") suspend fun recipe(@Path("id") id: Int): Recipe

    @GET("weeks") suspend fun weeks(@Query("from") from: String, @Query("count") count: Int): List<WeekSummary>
    @GET("weeks/{week}") suspend fun week(@Path("week") week: String): List<PlanEntry>
    @POST("weeks/{week}/entries") suspend fun addEntry(@Path("week") week: String, @Body body: EntryIn): PlanEntry
    @PATCH("plan/{id}") suspend fun patchEntry(@Path("id") id: Int, @Body body: JsonObject)
    @DELETE("plan/{id}") suspend fun deleteEntry(@Path("id") id: Int)
    @PUT("plan/{id}/rating") suspend fun putRating(@Path("id") id: Int, @Body body: RatingIn)
    @DELETE("plan/{id}/rating") suspend fun deleteRating(@Path("id") id: Int)
    @GET("ratings/pending") suspend fun pendingRatings(@Query("today") today: String): List<PendingRating>

    @POST("list") suspend fun list(@Body body: ListIn): List<ListItem>
    @GET("cart/default-week") suspend fun defaultCartWeek(): DefaultWeek
    @POST("drafts") suspend fun createDraft(@Body body: DraftIn): JobStarted
    @GET("drafts/{id}") suspend fun draft(@Path("id") id: Int): Draft
    @GET("weeks/{week}/draft") suspend fun weekDraft(@Path("week") week: String): Draft
    @PATCH("drafts/{id}/lines/{line}") suspend fun patchLine(@Path("id") id: Int, @Path("line") line: Int, @Body body: JsonObject): DraftLine
    @POST("drafts/{id}/lines/{line}/search") suspend fun searchLine(@Path("id") id: Int, @Path("line") line: Int, @Body body: SearchIn): List<Product>
    @POST("drafts/{id}/send") suspend fun sendDraft(@Path("id") id: Int): SentCart

    @POST("prep-plans") suspend fun startPrep(@Body body: PrepIn): JobStarted
    @GET("prep-plans/{id}") suspend fun prepPlan(@Path("id") id: Int): PrepPlan
    @GET("weeks/{week}/prep-plan") suspend fun weekPrepPlan(@Path("week") week: String): PrepPlan
    @PATCH("prep-plans/{id}/tasks/{task}") suspend fun tickTask(@Path("id") id: Int, @Path("task") task: String, @Body body: TaskDone): PrepTask
    @GET("plan/{id}/card") suspend fun card(@Path("id") id: Int): CookCard
}
