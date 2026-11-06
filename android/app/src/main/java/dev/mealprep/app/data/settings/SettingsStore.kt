package dev.mealprep.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** Per-phone notification settings (defaults on, Tonight optional, times adjustable). */
data class NotifPrefs(
    val rate: Boolean = true, val rateAt: LocalTime = LocalTime.of(9, 0),
    val tonight: Boolean = false, val tonightAt: LocalTime = LocalTime.of(16, 0),
    val cartReady: Boolean = true,
    /** "Time to check the staples" (off unless chosen): a weekly local notification that opens the shopping list. */
    val staples: Boolean = false, val staplesDay: DayOfWeek = DayOfWeek.SATURDAY, val staplesAt: LocalTime = LocalTime.of(9, 0),
)

data class Settings(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val token: String = "",
    val notif: NotifPrefs = NotifPrefs(),
    val loblawsHideWebViewMarker: Boolean = false,
    /** The Loblaws handoff always starts signed out (the only way the cart merges). On (default since 0.4.2): clear
     *  only loblaws.ca, so PC id remembers this phone (no code). Off: clear everything. */
    val loblawsKeepDeviceTrust: Boolean = true,
    val askedNotificationPermission: Boolean = false,
    /** The cookbook the last scanned recipe came from: the next scan's default "Which book?". */
    val lastBook: String? = null,
    /** Signed in with Google: the app session token (POST /auth/app-token). Blank = the household token above. */
    val session: String = "",
    /** The household the app acts for (X-Household; the switcher). null = the session's own. */
    val householdId: Int? = null,
    /** What GET /auth/me said last (shown in Settings, used offline): who, which household, what they may do. */
    val account: Account? = null,
    /** A Google sign-in in progress: the PKCE verifier of the challenge sent to /auth/login (kept on disk because the
     *  app may be closed while the browser is in front). */
    val pendingVerifier: String? = null,
) {
    val signedIn: Boolean get() = session.isNotBlank()
    val configured: Boolean get() = signedIn || (token.isNotBlank() && serverUrl.isNotBlank())
    /** Where calls go: signed in = the public address (works anywhere); else the home server with the household token. */
    val apiUrl: String get() = if (signedIn) ACCOUNT_URL else serverUrl
    val apiToken: String get() = if (signedIn) session else token
    companion object {
        const val DEFAULT_SERVER_URL = "http://192.168.1.101:8790"
        /** The server behind the public web app (Caddy strips /api). The household token is refused there by design. */
        const val ACCOUNT_URL = "https://mealprep.drwhom.ca/api"
    }
}

/** The signed-in person as GET /auth/me last described them (null permissions = not known yet: nothing is hidden). */
data class Account(
    val name: String? = null, val email: String? = null,
    val householdName: String? = null, val roles: List<String> = emptyList(),
    val permissions: Set<String>? = null,
)

fun normalizeServerUrl(s: String): String {
    val t = s.trim().trimEnd('/')
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
}

class SettingsStore(private val ds: DataStore<Preferences>) {
    val settings: Flow<Settings> = ds.data.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }.map(::read)

    suspend fun update(f: (Settings) -> Settings) {
        ds.edit { p -> write(p, f(read(p))) }
    }

    private object K {
        val url = stringPreferencesKey("server_url"); val token = stringPreferencesKey("token")
        val rate = booleanPreferencesKey("n_rate"); val rateAt = intPreferencesKey("n_rate_at")
        val tonight = booleanPreferencesKey("n_tonight"); val tonightAt = intPreferencesKey("n_tonight_at")
        val cartReady = booleanPreferencesKey("n_cart_ready")
        val staples = booleanPreferencesKey("n_staples"); val staplesDay = intPreferencesKey("n_staples_day")
        val staplesAt = intPreferencesKey("n_staples_at")
        val hideWv = booleanPreferencesKey("lob_hide_wv")
        // A new key: 0.4.0/0.4.1 saved "lob_keep_trust" = false with every settings change, so an off there can't be
        // told from "never touched". Both old Loblaws keys are ignored and removed.
        val keepTrust = booleanPreferencesKey("lob_device_trust")
        val legacy = listOf(booleanPreferencesKey("lob_signed_out"), booleanPreferencesKey("lob_keep_trust"))
        val asked = booleanPreferencesKey("asked_notif")
        val lastBook = stringPreferencesKey("last_book")
        val session = stringPreferencesKey("session"); val household = intPreferencesKey("household_id")
        val accName = stringPreferencesKey("acct_name"); val accEmail = stringPreferencesKey("acct_email")
        val accHousehold = stringPreferencesKey("acct_household"); val accRoles = stringPreferencesKey("acct_roles")
        val accPerms = stringSetPreferencesKey("acct_perms"); val accKnown = booleanPreferencesKey("acct_known")
        val verifier = stringPreferencesKey("pkce_verifier")
    }

    private fun t(min: Int?, d: LocalTime) = min?.let { LocalTime.of(it / 60, it % 60) } ?: d
    private fun m(t: LocalTime) = t.hour * 60 + t.minute

    private fun read(p: Preferences): Settings {
        val d = Settings(); val n = d.notif
        return Settings(
            serverUrl = p[K.url] ?: d.serverUrl, token = p[K.token] ?: "",
            notif = NotifPrefs(
                rate = p[K.rate] ?: n.rate, rateAt = t(p[K.rateAt], n.rateAt),
                tonight = p[K.tonight] ?: n.tonight, tonightAt = t(p[K.tonightAt], n.tonightAt),
                cartReady = p[K.cartReady] ?: n.cartReady,
                staples = p[K.staples] ?: n.staples,
                staplesDay = p[K.staplesDay]?.takeIf { it in 1..7 }?.let(DayOfWeek::of) ?: n.staplesDay,
                staplesAt = t(p[K.staplesAt], n.staplesAt),
            ),
            loblawsHideWebViewMarker = p[K.hideWv] ?: d.loblawsHideWebViewMarker,
            loblawsKeepDeviceTrust = p[K.keepTrust] ?: d.loblawsKeepDeviceTrust,
            askedNotificationPermission = p[K.asked] ?: false,
            lastBook = p[K.lastBook],
            session = p[K.session] ?: "",
            householdId = p[K.household],
            account = if (p[K.accKnown] == true) Account(p[K.accName], p[K.accEmail], p[K.accHousehold],
                p[K.accRoles]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(), p[K.accPerms]) else null,
            pendingVerifier = p[K.verifier],
        )
    }

    private fun write(p: MutablePreferences, s: Settings) {
        p[K.url] = s.serverUrl; p[K.token] = s.token
        p[K.rate] = s.notif.rate; p[K.rateAt] = m(s.notif.rateAt)
        p[K.tonight] = s.notif.tonight; p[K.tonightAt] = m(s.notif.tonightAt)
        p[K.cartReady] = s.notif.cartReady
        p[K.staples] = s.notif.staples; p[K.staplesDay] = s.notif.staplesDay.value; p[K.staplesAt] = m(s.notif.staplesAt)
        p[K.hideWv] = s.loblawsHideWebViewMarker
        p[K.keepTrust] = s.loblawsKeepDeviceTrust
        K.legacy.forEach { p.remove(it) }
        p[K.asked] = s.askedNotificationPermission
        s.lastBook?.let { p[K.lastBook] = it } ?: p.remove(K.lastBook)
        p[K.session] = s.session
        s.householdId?.let { p[K.household] = it } ?: p.remove(K.household)
        val a = s.account
        p[K.accKnown] = a != null
        a?.name?.let { p[K.accName] = it } ?: p.remove(K.accName)
        a?.email?.let { p[K.accEmail] = it } ?: p.remove(K.accEmail)
        a?.householdName?.let { p[K.accHousehold] = it } ?: p.remove(K.accHousehold)
        a?.let { p[K.accRoles] = it.roles.joinToString(",") } ?: p.remove(K.accRoles)
        a?.permissions?.let { p[K.accPerms] = it } ?: p.remove(K.accPerms)
        s.pendingVerifier?.let { p[K.verifier] = it } ?: p.remove(K.verifier)
    }

    companion object {
        fun create(context: Context) =
            SettingsStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") })
    }
}
