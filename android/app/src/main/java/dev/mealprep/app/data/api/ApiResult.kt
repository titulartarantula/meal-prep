package dev.mealprep.app.data.api

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {
    data object NotConfigured : ApiError
    /** Couldn't connect: off the home network, or the server is down. The request was never processed. */
    data object Unreachable : ApiError
    /** Connected but no answer in time: the server may still finish the work. */
    data object TimedOut : ApiError
    data object Unauthorized : ApiError
    data class Http(val code: Int, val detail: String?) : ApiError
    data class Other(val message: String) : ApiError
}

fun ApiError.userMessage(): String = when (this) {
    ApiError.NotConfigured -> "Set the server address and token in Settings."
    ApiError.Unreachable -> "Can't reach the meal-prep server. Are you on home Wi-Fi (or WireGuard)?"
    ApiError.TimedOut -> "The server is taking too long. It may still finish — check again in a minute."
    ApiError.Unauthorized -> "The server rejected the token. Check it in Settings."
    // The server's 502 details are "<step> failed: SomeError: …" — internals, not sentences. Other details
    // (404/409/422) are written for people and shown as they are.
    is ApiError.Http -> if (code == 502) "The server couldn't get what it needed from another site. Try again later."
        else detail ?: "Server error ($code)."
    is ApiError.Other -> message
}

suspend fun <T> apiCall(block: suspend () -> T): ApiResult<T> = try {
    ApiResult.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: HttpException) {
    ApiResult.Err(httpError(e.code(), e.response()?.errorBody()?.string()))
} catch (e: SocketTimeoutException) {
    // OkHttp reports a connect timeout as "failed to connect…"/"Connect timed out": that's Unreachable.
    ApiResult.Err(if (e.message.orEmpty().contains("connect", ignoreCase = true)) ApiError.Unreachable else ApiError.TimedOut)
} catch (e: SerializationException) {
    ApiResult.Err(ApiError.Other("The server sent a reply this app doesn't understand."))
} catch (e: ConnectException) {
    ApiResult.Err(ApiError.Unreachable)
} catch (e: UnknownHostException) {
    ApiResult.Err(ApiError.Unreachable)
} catch (e: NoRouteToHostException) {
    ApiResult.Err(ApiError.Unreachable)
} catch (e: IOException) {
    // Any other I/O failure (reset, truncated reply...) may have happened after the server accepted the request,
    // so it is TimedOut ("may still finish"), never the safely-retryable Unreachable.
    val msg = e.message.orEmpty()
    ApiResult.Err(if (msg.contains("CLEARTEXT", ignoreCase = true)) ApiError.Other("This server address isn't allowed (only the home server is).") else ApiError.TimedOut)
} catch (e: Exception) {
    // e.g. Retrofit's NullPointerException on an empty 200 body for a non-Unit call.
    ApiResult.Err(ApiError.Other("Something went wrong talking to the server."))
}

internal fun httpError(code: Int, body: String?): ApiError {
    if (code == 401) return ApiError.Unauthorized
    val detail = runCatching {
        when (val d = Http.json.parseToJsonElement(body!!).jsonObject["detail"]) {
            is JsonPrimitive -> d.content
            is JsonArray -> (d.first() as JsonObject)["msg"]!!.jsonPrimitive.content
            else -> null
        }
    }.getOrNull()
    return ApiError.Http(code, detail)
}
