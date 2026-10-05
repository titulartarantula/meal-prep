package dev.mealprep.app.data.api

import dev.mealprep.app.fixture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiErrorTest {
    private val server = MockWebServer()
    private lateinit var api: MealPrepApi

    @Before fun setUp() { server.start(); api = Http.api(server.url("/").toString(), Http.client({ "tok" }, readTimeoutSec = 1, longReadTimeoutSec = 1)) }
    @After fun tearDown() { server.close() }

    private fun reply(code: Int, body: String) = server.enqueue(MockResponse.Builder().code(code).body(body).build())
    private suspend fun err(block: suspend () -> Any) = (apiCall { block() } as ApiResult.Err).error

    @Test fun `401 is Unauthorized`() = runTest {
        reply(401, """{"detail":"Unauthorized"}""")
        assertEquals(ApiError.Unauthorized, err { api.weeks("2026-10-04", 1) })
    }

    @Test fun `FastAPI detail strings and validation lists become the message`() = runTest {
        reply(422, fixture("error_422_detail.json"))
        assertEquals(ApiError.Http(422, "no NYT Cooking link found"), err { api.share(ShareIn("x")) })
        reply(422, fixture("error_422_validation.json"))
        assertEquals(ApiError.Http(422, "Input should be a valid integer"), err { api.putRating(1, RatingIn(4)) })
        reply(500, "Internal Server Error")
        assertEquals(ApiError.Http(500, null), err { api.weeks("2026-10-04", 1) })
    }

    @Test fun `nothing listening is Unreachable`() = runTest {
        val dead = Http.api("http://127.0.0.1:1/", Http.client({ "tok" }))
        assertEquals(ApiError.Unreachable, (apiCall { dead.health() } as ApiResult.Err).error)
    }

    @Test fun `slow answer is TimedOut, not Unreachable`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("{}").headersDelay(3, TimeUnit.SECONDS).build())
        assertEquals(ApiError.TimedOut, err { api.health() })
    }

    @Test fun `connection dropped after the request is TimedOut, not Unreachable`() = runTest {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        assertEquals(ApiError.TimedOut, err { api.share(ShareIn("x")) })
    }

    @Test fun `empty 200 body on a non-Unit call is Other, not a crash, and names no exception`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).build())
        val e = err { api.health() }
        assertTrue(e is ApiError.Other)
        assertEquals("The server sent a reply this app doesn't understand.", e.userMessage())
    }

    @Test fun `an unexpected exception's message names no exception`() = runTest {
        assertEquals("Something went wrong talking to the server.", err { throw IllegalStateException("Expected BEGIN_OBJECT") }.userMessage())
    }

    @Test fun `a reply the app can't parse names no exception`() = runTest {
        reply(200, "[1,2]")
        val m = err { api.health() }.userMessage()
        assertEquals("The server sent a reply this app doesn't understand.", m)
    }

    @Test fun `502 detail with an exception name becomes a plain sentence`() {
        val m = ApiError.Http(502, "PC Express cart failed: TimeoutError: x").userMessage()
        assertFalse(m, m.contains("Error") || m.contains(":"))
        assertTrue(m.endsWith("Try again later."))
    }

    @Test fun `nullable server columns decode with defaults`() {
        val d = Http.json.decodeFromString<DraftLine>(
            """{"id":1,"item_key":null,"name":null,"product":{"code":"c","name":null},"quantity":null}""")
        assertEquals("", d.itemKey); assertEquals("", d.name); assertEquals("", d.product!!.name); assertEquals(null, d.quantity)
    }

    @Test fun `messages are plain`() {
        assertTrue(ApiError.Unreachable.userMessage().contains("home Wi-Fi"))
        assertTrue(ApiError.Unauthorized.userMessage().contains("token"))
        assertEquals("no NYT Cooking link found", ApiError.Http(422, "no NYT Cooking link found").userMessage())
        assertEquals("Server error (500).", ApiError.Http(500, null).userMessage())
    }
}
