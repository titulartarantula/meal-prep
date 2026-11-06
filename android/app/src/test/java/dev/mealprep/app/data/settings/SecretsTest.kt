package dev.mealprep.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings in memory (several writes per test; the Windows build host can't rename DataStore's temp file that fast). */
private class MemoryPrefs : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    private val lock = Mutex()
    var writes = 0
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        lock.withLock { transform(state.value).also { if (it != state.value) writes++; state.value = it } }
}

class SecretsTest {
    private val token = stringPreferencesKey("token"); private val session = stringPreferencesKey("session")
    private val verifier = stringPreferencesKey("pkce_verifier")

    @Test fun `tokens are saved encrypted and read back`() = runTest {
        val ds = MemoryPrefs(); val store = SettingsStore(ds, testCipher())
        store.update { it.copy(token = "household-tok", session = "sess-abc", pendingVerifier = "v".repeat(86)) }
        val raw = ds.data.first()
        for (k in listOf(token, session, verifier)) {
            assertTrue(raw[k]!!.startsWith(Secrets.PREFIX))
        }
        assertFalse(raw[token]!!.contains("household-tok")); assertFalse(raw[session]!!.contains("sess-abc"))
        val s = store.settings.first()
        assertEquals("household-tok", s.token); assertEquals("sess-abc", s.session); assertEquals("v".repeat(86), s.pendingVerifier)
        // an unchanged secret keeps its stored text; blank stays blank (not encrypted)
        store.update { it.copy(lastBook = "Salt Fat Acid Heat", session = "") }
        val raw2 = ds.data.first()
        assertEquals(raw[token], raw2[token]); assertEquals("", raw2[session])
    }

    @Test fun `every write gets a new IV`() {
        val c = testCipher(); val secrets = Secrets(c)
        val a = secrets.seal("same"); val b = secrets.seal("same")
        assertNotEquals(a, b)
        fun iv(s: String) = Base64.getDecoder().decode(s.removePrefix(Secrets.PREFIX)).copyOf(12).toList()
        assertNotEquals(iv(a), iv(b))
        // a fresh reader (no cache) decrypts both
        assertEquals("same", Secrets(c).open(a)); assertEquals("same", Secrets(c).open(b))
    }

    @Test fun `plain secrets from before 0_17_0 are encrypted once and read the same`() = runTest {
        val ds = MemoryPrefs()
        ds.edit { it[token] = "old-tok"; it[session] = "old-sess"; it[intPreferencesKey("household_id")] = 7 }
        val store = SettingsStore(ds, testCipher())
        assertEquals("old-sess", store.settings.first().session)   // readable before the migration too
        assertEquals(LostSecrets(), store.migrateSecrets())
        val raw = ds.data.first()
        assertTrue(raw[token]!!.startsWith(Secrets.PREFIX) && raw[session]!!.startsWith(Secrets.PREFIX))
        val s = store.settings.first()
        assertEquals("old-tok", s.token); assertEquals("old-sess", s.session); assertEquals(7, s.householdId)
        // nothing left to do: the next start doesn't write
        val writes = ds.writes
        assertEquals(LostSecrets(), store.migrateSecrets())
        assertEquals(writes, ds.writes)
    }

    @Test fun `secrets that can't be decrypted are dropped and the account with them`() = runTest {
        val ds = MemoryPrefs()
        SettingsStore(ds, testCipher()).update {
            it.copy(token = "tok", session = "sess", householdId = 7, account = Account("Sam", roles = listOf("owner")))
        }
        // A restore onto another phone: same file, a different (new) Keystore key.
        val store = SettingsStore(ds, testCipher())
        val before = store.settings.first()
        assertEquals("", before.session); assertEquals("", before.token); assertFalse(before.configured)
        assertEquals(LostSecrets(session = true, token = true), store.migrateSecrets())
        val raw = ds.data.first()
        assertNull(raw[token]); assertNull(raw[session]); assertNull(raw[intPreferencesKey("household_id")])
        assertNull(raw[booleanPreferencesKey("acct_known")]); assertNull(store.settings.first().account)
        // and a corrupt value is treated the same, without a crash
        ds.edit { it[session] = Secrets.PREFIX + "not base64 %%" }
        assertEquals("", store.settings.first().session)
        assertEquals(LostSecrets(session = true), store.migrateSecrets())
    }

    @Test fun `no key at all reads as not set`() {
        val gone = AesGcmCipher(encryptKey = { error("unused") }, decryptKey = { null })
        assertNull(Secrets(gone).open(Secrets(testCipher()).seal("x")))
        assertEquals("plain", Secrets(gone).open("plain"))
    }
}
