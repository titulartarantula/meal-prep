package dev.mealprep.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.Preferences
import java.io.IOException
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `defaults then round trip`() = runTest {
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.root, "s.preferences_pb") })
        val d = store.settings.first()
        assertEquals("http://192.168.1.101:8790", d.serverUrl)
        assertFalse(d.configured)
        assertTrue(d.notif.thaw && d.notif.rate && d.notif.cartReady && !d.notif.tonight)
        assertEquals(LocalTime.of(20, 0), d.notif.thawAt)
        store.update { it.copy(token = "abc", notif = it.notif.copy(tonight = true, tonightAt = LocalTime.of(16, 30))) }
        val s = store.settings.first()
        assertTrue(s.configured)
        assertEquals(LocalTime.of(16, 30), s.notif.tonightAt)
        assertTrue(s.notif.tonight)
        assertFalse(s.notif.staples)
    }

    // One update per store: on the Windows build host a second quick write can fail to rename DataStore's temp file.
    @Test fun `staples reminder and keep-device-trust round trip`() = runTest {
        val store = SettingsStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.root, "r.preferences_pb") })
        store.update { it.copy(loblawsKeepDeviceTrust = true,
            notif = it.notif.copy(staples = true, staplesDay = java.time.DayOfWeek.FRIDAY, staplesAt = LocalTime.of(18, 15))) }
        assertTrue(store.settings.first().loblawsKeepDeviceTrust)
        val r = store.settings.first().notif
        assertTrue(r.staples)
        assertEquals(java.time.DayOfWeek.FRIDAY, r.staplesDay)
        assertEquals(LocalTime.of(18, 15), r.staplesAt)
    }

    @Test fun `device trust is on for phones that had the 0_4_1 keys`() = runTest {
        // 0.4.0/0.4.1 wrote both Loblaws switches with every settings change; their values no longer count.
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.root, "old.preferences_pb") }
        ds.edit { p ->
            p[booleanPreferencesKey("lob_signed_out")] = false; p[booleanPreferencesKey("lob_keep_trust")] = false
            p[stringPreferencesKey("token")] = "abc"
        }
        val s = SettingsStore(ds).settings.first()
        assertTrue(s.loblawsKeepDeviceTrust)
        assertEquals("abc", s.token)
    }

    @Test fun `device trust turned off stays off and the old keys are dropped`() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { java.io.File(tmp.root, "off.preferences_pb") }
        val store = SettingsStore(ds)
        store.update { it.copy(loblawsKeepDeviceTrust = false) }
        assertFalse(store.settings.first().loblawsKeepDeviceTrust)
        val raw = ds.data.first()
        assertEquals(null, raw[booleanPreferencesKey("lob_signed_out")])
        assertEquals(null, raw[booleanPreferencesKey("lob_keep_trust")])
    }

    @Test fun `server url is normalized`() {
        assertEquals("http://192.168.1.101:8790", normalizeServerUrl(" 192.168.1.101:8790/ "))
        assertEquals("https://meals.example.ca", normalizeServerUrl("https://meals.example.ca/"))
    }

    private class FailingStore(private val e: Throwable) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw e }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw e
    }

    @Test fun `io errors read as defaults`() = runTest {
        val s = SettingsStore(FailingStore(IOException("disk"))).settings.first()
        assertEquals(Settings(), s)
    }

    @Test fun `other errors are not swallowed`() = runTest {
        try {
            SettingsStore(FailingStore(IllegalStateException("boom"))).settings.first()
            fail("expected the error to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }
}
