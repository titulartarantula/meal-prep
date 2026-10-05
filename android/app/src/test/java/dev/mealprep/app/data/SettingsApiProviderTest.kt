package dev.mealprep.app.data

import dev.mealprep.app.data.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsApiProviderTest {
    private val a = MockWebServer().apply { start() }
    private val b = MockWebServer().apply { start() }
    @After fun tearDown() { a.close(); b.close() }

    private fun ok() = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("[]").build()
    private fun url(s: MockWebServer) = s.url("/").toString().trimEnd('/')

    @Test fun `null until configured, then follows url and token changes`() = runBlocking {
        a.enqueue(ok()); a.enqueue(ok()); b.enqueue(ok())
        val flow = MutableStateFlow(Settings(serverUrl = url(a), token = ""))
        val provider = SettingsApiProvider(flow)
        assertNull(provider.api())

        flow.value = flow.value.copy(token = "one")
        val api = provider.api()
        assertNotNull(api)
        api!!.recipes("newest")
        assertEquals("Bearer one", a.takeRequest().headers["Authorization"])

        // token change applies to the next request, same server
        flow.value = flow.value.copy(token = "two")
        provider.api()!!.recipes("newest")
        assertEquals("Bearer two", a.takeRequest().headers["Authorization"])

        // URL change -> requests go to the new server
        flow.value = flow.value.copy(serverUrl = url(b))
        provider.api()!!.recipes("newest")
        assertEquals("Bearer two", b.takeRequest().headers["Authorization"])
        assertEquals(2, a.requestCount)
    }
}
