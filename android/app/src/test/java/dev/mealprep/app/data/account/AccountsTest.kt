package dev.mealprep.app.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.mealprep.app.data.ApiProvider
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.cache.CacheDb
import dev.mealprep.app.data.cache.CachedDoc
import dev.mealprep.app.data.settings.LostSecrets
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.data.settings.SettingsStore
import dev.mealprep.app.data.settings.testCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/** Settings in memory (several writes per test; the Windows build host can't rename DataStore's temp file that fast). */
private class MemoryPrefs : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { state.value = it } }
}

@RunWith(RobolectricTestRunner::class)
class AccountsTest {
    private val app = "dev.mealprep.app.debug"
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CacheDb::class.java)
        .allowMainThreadQueries().build()
    @Volatile private var tokenReply: Pair<Int, String> = 200 to """{"token":"sess-1","expires_at":"2026-12-05T00:00:00Z","household_id":7}"""
    @Volatile private var meReply: Pair<Int, String> = 200 to ME

    @After fun tearDown() { server.close(); db.close() }

    private fun reply(r: Pair<Int, String>) = MockResponse.Builder().code(r.first).addHeader("Content-Type", "application/json").body(r.second).build()

    private fun TestScope.accounts(): Triple<Accounts, SettingsStore, StateFlow<Settings>> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when ("${request.method} ${request.url.encodedPath}") {
                    "POST /auth/app-token" -> reply(tokenReply)
                    "GET /auth/me" -> reply(meReply)
                    "PUT /auth/household" -> reply(200 to """{"household_id":8}""")
                    "POST /auth/logout" -> MockResponse.Builder().code(204).build()
                    else -> reply(404 to """{"detail":"no"}""")
                }
            }
        }
        server.start()
        val store = SettingsStore(MemoryPrefs(), testCipher())
        val settings = store.settings.stateIn(backgroundScope, SharingStarted.Eagerly, Settings())
        val url = server.url("/").toString()
        // As SettingsApiProvider, but the signed-in address is the fake server.
        val client = Http.client({ settings.value.apiToken }, household = { settings.value.takeIf { it.signedIn }?.householdId })
        val repo = Repository(ApiProvider { if (settings.value.configured) Http.api(url, client) else null }, db.cache())
        return Triple(Accounts(store, settings, repo, app, publicApi = { Http.api(url, Http.client({ "" })) }), store, settings)
    }

    @Test fun `sign-in trades the code and the kept verifier for a session, then reads who it is`() = runTest {
        val (acc, _, settings) = accounts()
        db.cache().put(CachedDoc("week:2026-11-01", "{}", 1)); db.cache().put(CachedDoc("local:later", "{}", 1))
        val url = acc.begin("inv-token-0123456789")
        assertTrue(url.startsWith(Settings.ACCOUNT_URL + "/auth/login?client=android&app=$app&app_challenge="))
        assertTrue(url.endsWith("&invite=inv-token-0123456789"))
        assertEquals(SignInStatus.InBrowser, acc.status.value)
        val verifier = settings.first { it.pendingVerifier != null }.pendingVerifier!!
        assertTrue(url.contains("app_challenge=" + Pkce.challenge(verifier)))

        acc.complete("$app://auth?code=one-time-code-0123456789")
        assertEquals(SignInStatus.Done, acc.status.value)
        val token = requests.first { it.url.encodedPath == "/auth/app-token" }
        assertNull(token.headers["Authorization"])
        assertEquals("""{"code":"one-time-code-0123456789","verifier":"$verifier"}""", token.body!!.utf8())
        val s = settings.first { it.account != null }
        assertEquals("sess-1", s.session); assertTrue(s.signedIn); assertNull(s.pendingVerifier)
        assertEquals(7, s.householdId)
        assertEquals("Sam Example", s.account!!.name); assertEquals("Testers", s.account!!.householdName)
        assertEquals(setOf("plan.read", "rate.self"), s.account!!.permissions)
        val me = requests.first { it.url.encodedPath == "/auth/me" }
        assertEquals("Bearer sess-1", me.headers["Authorization"]); assertEquals("7", me.headers["X-Household"])
        // the other account's saved copies are gone; the phone's own notes stay
        assertNull(db.cache().get("week:2026-11-01")); assertNotNull(db.cache().get("local:later"))
    }

    @Test fun `saved secrets the phone couldn't read - Sign in says why, or the dialog when the token is left`() = runTest {
        val (acc, store, settings) = accounts()
        acc.secretsLost(LostSecrets(session = true))
        assertEquals("This phone couldn't read your saved sign-in. Sign in again.", (acc.status.value as SignInStatus.Failed).message)
        assertFalse(acc.needsSignIn.value)
        acc.resetStatus()
        acc.secretsLost(LostSecrets(token = true))
        assertTrue((acc.status.value as SignInStatus.Failed).message.contains("household token"))
        acc.resetStatus()
        store.update { it.copy(token = "tok") }; settings.first { it.configured }
        acc.secretsLost(LostSecrets(session = true))
        assertTrue(acc.needsSignIn.value); assertEquals(SignInStatus.Idle, acc.status.value)
    }

    @Test fun `not invited, cancelled and a refused code are explained and store nothing`() = runTest {
        val (acc, _, settings) = accounts()
        acc.begin()
        acc.complete("$app://auth?error=not_invited&message=This+Google+account+isn%27t+in+a+household+yet.")
        val f = acc.status.value as SignInStatus.Failed
        assertTrue(f.notInvited && f.message.contains("Ask your household head for an invite"))
        acc.begin(); acc.complete("$app://auth?error=cancelled")
        assertTrue((acc.status.value as SignInStatus.Failed).message.startsWith("Sign-in was cancelled"))
        tokenReply = 400 to """{"detail":"This sign-in has expired; sign in again."}"""
        acc.begin(); acc.complete("$app://auth?code=one-time-code-0123456789")
        assertEquals("That sign-in expired before it finished. Sign in again.", (acc.status.value as SignInStatus.Failed).message)
        // a code without a sign-in started here (no verifier kept) isn't sent anywhere
        val before = requests.size
        acc.complete("$app://auth?code=one-time-code-0123456789")
        assertEquals(before, requests.size)
        // another app's return is ignored
        acc.complete("dev.mealprep.app://auth?code=x")
        val s = settings.value
        assertFalse(s.signedIn); assertNull(s.account); assertFalse(s.configured)
    }

    @Test fun `switching household, a refused session and signing out`() = runTest {
        val (acc, store, settings) = accounts()
        acc.begin(); acc.complete("$app://auth?code=one-time-code-0123456789")
        settings.first { it.account != null }
        meReply = 200 to ME.replace("\"id\":7,\"name\":\"Testers\"", "\"id\":8,\"name\":\"Cousins\"")
        assertTrue(acc.switchHousehold(8) is ApiResult.Ok)
        assertEquals("""{"household_id":8}""", requests.last { it.url.encodedPath == "/auth/household" }.body!!.utf8())
        val s = settings.first { it.householdId == 8 && it.account?.householdName == "Cousins" }
        assertEquals(8, s.householdId)
        assertEquals("8", requests.last { it.url.encodedPath == "/auth/me" }.headers["X-Household"])

        acc.sessionRefused()
        assertTrue(acc.needsSignIn.value)
        acc.signOut()
        assertFalse(acc.needsSignIn.value)
        assertEquals("Bearer sess-1", requests.last { it.url.encodedPath == "/auth/logout" }.headers["Authorization"])
        val out = settings.first { !it.signedIn }
        assertNull(out.householdId); assertNull(out.account); assertFalse(out.configured)
        // the household token still works afterwards: its /auth/me has no user and no X-Household
        meReply = 200 to """{"via":"legacy","user":null,"households":[],"household":{"id":1,"name":"Home"},"member_id":1,"roles":["owner"],"permissions":["cart.send","plan.edit"]}"""
        store.update { it.copy(token = "tok") }
        settings.first { it.configured }
        assertTrue(acc.refresh() is ApiResult.Ok)
        val legacy = settings.first { it.account != null }
        assertNull(legacy.householdId); assertNull(legacy.account!!.name); assertEquals(setOf("cart.send", "plan.edit"), legacy.account!!.permissions)
        val me = requests.last { it.url.encodedPath == "/auth/me" }
        assertEquals("Bearer tok", me.headers["Authorization"]); assertNull(me.headers["X-Household"])
        // a refused household token isn't a lost sign-in
        acc.sessionRefused(); assertFalse(acc.needsSignIn.value)
    }

    private companion object {
        const val ME = """{"via":"bearer","user":{"id":3,"email":"sam@example.com","name":"Sam Example","picture":null},
            "households":[{"id":7,"name":"Testers","member_id":11},{"id":8,"name":"Cousins","member_id":12}],
            "household":{"id":7,"name":"Testers","store_id":"1092"},"member_id":11,"roles":["contributor"],
            "permissions":["plan.read","rate.self"]}"""
    }
}
