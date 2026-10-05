package dev.mealprep.app.ui.setup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.await
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.SettingsApiProvider
import dev.mealprep.app.data.cache.CacheDb
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.data.settings.SettingsStore
import dev.mealprep.app.fixture
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CacheDb::class.java)
        .allowMainThreadQueries().build()
    @After fun tearDown() { server.close(); db.close() }

    private fun serve(weeksCode: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/health" -> MockResponse.Builder().code(200).body("""{"ok":true,"provider":"claude-cli"}""").build()
                "/weeks" -> if (weeksCode == 200) MockResponse.Builder().code(200).body(fixture("weeks.json")).build()
                            else MockResponse.Builder().code(weeksCode).body("""{"detail":"Unauthorized"}""").build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.vm(url: String, token: String): Pair<SetupViewModel, SettingsStore> {
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb") })
        val settings = store.settings.stateIn(backgroundScope, SharingStarted.Eagerly, Settings())
        val repo = Repository(SettingsApiProvider(settings), db.cache())
        return SetupViewModel(store, settings, repo, Settings()).apply { setUrl(url); setToken(token) } to store
    }

    @Test fun `good token connects and is saved`() = runTest {
        serve(200)
        val (vm, store) = vm(server.url("/").toString(), " tok ")
        vm.saveAndTest()
        val s = vm.state.await { !it.testing && it.result != null }
        assertTrue(s.ok); assertEquals("Connected.", s.result)
        assertEquals("tok", store.settings.first().token)
        server.takeRequest()                                                    // GET /health
        assertEquals("Bearer tok", server.takeRequest().headers["Authorization"]) // GET /weeks
    }

    @Test fun `rejected token is reported`() = runTest {
        serve(401)
        val (vm, _) = vm(server.url("/").toString(), "wrong")
        vm.saveAndTest()
        val s = vm.state.await { !it.testing && it.result != null }
        assertFalse(s.ok); assertEquals("Server found, but it rejected the token.", s.result)
    }

    @Test fun `no server is reported plainly`() = runTest {
        val (vm, _) = vm("127.0.0.1:1", "tok")
        vm.saveAndTest()
        val s = vm.state.await { !it.testing && it.result != null }
        assertFalse(s.ok); assertTrue(s.result!!.contains("home Wi-Fi"))
        assertEquals("http://127.0.0.1:1", s.url)
    }
}
