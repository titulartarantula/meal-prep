package dev.mealprep.app.data.api

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

object Http {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true      // the server may add fields
        coerceInputValues = true      // null for a non-null field with a default → the default
        explicitNulls = false         // omit nulls when sending (PATCH "day": null is built by hand in Bodies)
        encodeDefaults = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    // Calls that wait on the server's AI (up to its 300 s timeout).
    private val LONG_CALL = Regex("""/recipes/(share|photo|\d+/pages)$""")
    fun isLongCall(path: String): Boolean = LONG_CALL.containsMatchIn(path)

    private val HEALTH = Regex("""/health$""")

    fun client(
        token: () -> String,
        connectTimeoutSec: Long = 5,
        readTimeoutSec: Long = 30,
        longReadTimeoutSec: Long = 330,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val t = token()
            val orig = chain.request()
            // Every call except GET /health carries the bearer token.
            val skipAuth = t.isBlank() || (orig.method == "GET" && HEALTH.containsMatchIn(orig.url.encodedPath))
            val req = if (skipAuth) orig else orig.newBuilder().header("Authorization", "Bearer $t").build()
            val c = if (isLongCall(req.url.encodedPath))
                chain.withReadTimeout(longReadTimeoutSec.toInt(), TimeUnit.SECONDS).withWriteTimeout(120, TimeUnit.SECONDS)
                else chain
            c.proceed(req)
        }
        .build()

    fun api(baseUrl: String, client: OkHttpClient): MealPrepApi = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(MealPrepApi::class.java)

    /** Pages in upload order as page01.jpg, page02.jpg … (the server reads them in this order). */
    fun pageParts(pages: List<File>): List<MultipartBody.Part> = pages.mapIndexed { i, f ->
        MultipartBody.Part.createFormData("files", "page%02d.jpg".format(i + 1), f.asRequestBody("image/jpeg".toMediaType()))
    }

    fun textPart(v: String): RequestBody = v.toRequestBody("text/plain".toMediaType())
}
