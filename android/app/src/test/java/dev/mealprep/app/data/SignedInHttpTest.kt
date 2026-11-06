package dev.mealprep.app.data

import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedInHttpTest {
    private val server = MockWebServer().apply { start() }
    @After fun tearDown() { server.close() }

    private fun reply(code: Int) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body("[]").build()

    @Test fun `signed in = the public address and the session, else the home server and the household token`() {
        val home = Settings(serverUrl = "http://home-server.example:8790", token = "tok")
        assertTrue(home.configured); assertFalse(home.signedIn)
        assertEquals("http://home-server.example:8790", home.apiUrl); assertEquals("tok", home.apiToken)
        val signed = home.copy(session = "sess")
        assertTrue(signed.signedIn); assertEquals(Settings.ACCOUNT_URL, signed.apiUrl); assertEquals("sess", signed.apiToken)
        assertTrue(Settings(session = "sess", token = "").configured)
        assertEquals("https://mealprep.drwhom.ca/api", Settings.ACCOUNT_URL)
    }

    @Test fun `the household header goes with every call, and a 401 is reported`() = runBlocking {
        server.enqueue(reply(200)); server.enqueue(reply(401)); server.enqueue(reply(200))
        var household: Int? = 7
        var refused = 0
        val api = Http.api(server.url("/").toString(), Http.client({ "sess" }, household = { household }, onUnauthorized = { refused++ }))
        api.recipes("newest")
        val first = server.takeRequest()
        assertEquals("Bearer sess", first.headers["Authorization"]); assertEquals("7", first.headers["X-Household"])
        runCatching { api.recipes("newest") }
        assertEquals(1, refused)
        household = null
        api.recipes("newest")
        server.takeRequest()
        assertNull(server.takeRequest().headers["X-Household"])
    }

    @Test fun `the provider only reports a refused session, not a refused household token`() = runBlocking {
        repeat(2) { server.enqueue(reply(401)) }
        val flow = MutableStateFlow(Settings(serverUrl = server.url("/").toString().trimEnd('/'), token = "tok"))
        var refused = 0
        val provider = SettingsApiProvider(flow) { refused++ }
        runCatching { provider.api()!!.recipes("newest") }
        assertEquals(0, refused)
        assertNull(server.takeRequest().headers["X-Household"])
        // signed in, the provider would call the public address; the 401 handling itself is the client's (above)
        flow.value = flow.value.copy(session = "sess", householdId = 3)
        assertEquals(Settings.ACCOUNT_URL, flow.value.apiUrl)
    }
}
