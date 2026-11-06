package dev.mealprep.app.data.account

import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.AppTokenIn
import dev.mealprep.app.data.api.HouseholdPick
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.MealPrepApi
import dev.mealprep.app.data.api.Me
import dev.mealprep.app.data.api.apiCall
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.data.settings.Account
import dev.mealprep.app.data.settings.LostSecrets
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.data.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Where a Google sign-in is. */
sealed interface SignInStatus {
    data object Idle : SignInStatus
    /** The browser is open at Google. */
    data object InBrowser : SignInStatus
    /** Back in the app, trading the code for a session. */
    data object Working : SignInStatus
    data object Done : SignInStatus
    data class Failed(val message: String, val notInvited: Boolean = false) : SignInStatus
}

/**
 * The signed-in account: Google sign-in (the web's round trip), the session token, the household switcher, sign-out,
 * and what the person may do (GET /auth/me). The household token (home network) keeps working without any of this.
 */
class Accounts(
    private val store: SettingsStore,
    private val settings: StateFlow<Settings>,
    private val repo: Repository,
    /** The app's application id = its return URL scheme (release and debug differ). */
    private val appId: String,
    /** POST /auth/app-token, sent to the public address without a token. */
    private val publicApi: () -> MealPrepApi = { Http.api(Settings.ACCOUNT_URL, Http.client({ "" })) },
) {
    private val _status = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    val status: StateFlow<SignInStatus> = _status.asStateFlow()

    /** A signed-in call was refused (401): the session ended or expired. The app asks to sign in again. */
    private val _needsSignIn = MutableStateFlow(false)
    val needsSignIn: StateFlow<Boolean> = _needsSignIn.asStateFlow()

    fun sessionRefused() { if (settings.value.signedIn) _needsSignIn.value = true }
    fun dismissSignInPrompt() { _needsSignIn.value = false }

    /** At start: saved secrets this phone couldn't decrypt were dropped (a restore, another phone, a corrupt value).
     *  Still set up (the household token is left): the Sign in again dialog; else the Sign in screen says why. */
    fun secretsLost(lost: LostSecrets) {
        if (!lost.session && !lost.token) return
        if (settings.value.configured) { if (lost.session) _needsSignIn.value = true; return }
        _status.value = SignInStatus.Failed(
            if (lost.session) "This phone couldn't read your saved sign-in. Sign in again."
            else "This phone couldn't read the saved household token. Sign in, or enter the token again.")
    }

    /** Starts a sign-in: a new verifier is kept (on disk, the app may be closed meanwhile); returns the page to open. */
    suspend fun begin(invite: String? = null): String {
        val v = Pkce.verifier()
        store.update { it.copy(pendingVerifier = v) }
        _status.value = SignInStatus.InBrowser
        return SignInLinks.loginUrl(Settings.ACCOUNT_URL, appId, Pkce.challenge(v), invite)
    }

    /** The browser came back: [url] = `<appId>://auth?code=…` (or ?error=…). Trades the code for a session. */
    suspend fun complete(url: String) {
        val r = SignInLinks.parseReturn(url, appId) ?: return
        val verifier = store.settings.first().pendingVerifier   // from disk: the app may have restarted meanwhile
        store.update { it.copy(pendingVerifier = null) }
        when (r) {
            is SignInReturn.Failed -> _status.value = SignInStatus.Failed(SignInLinks.failureText(r), r.kind == SignInLinks.NOT_INVITED)
            is SignInReturn.Code -> {
                if (verifier == null) {
                    _status.value = SignInStatus.Failed("That sign-in was started on another try. Sign in again.")
                    return
                }
                _status.value = SignInStatus.Working
                when (val t = apiCall { publicApi().appToken(AppTokenIn(r.code, verifier)) }) {
                    is ApiResult.Err -> _status.value = SignInStatus.Failed(signInError(t.error))
                    is ApiResult.Ok -> {
                        repo.forgetServerCopies()
                        store.update { it.copy(session = t.value.token, householdId = t.value.householdId, account = null) }
                        settings.first { it.session == t.value.token }   // the API provider reads this flow
                        _needsSignIn.value = false
                        refresh()
                        _status.value = SignInStatus.Done
                    }
                }
            }
        }
    }

    /** The sign-in screen showed its result. */
    fun resetStatus() { _status.value = SignInStatus.Idle }

    /** GET /auth/me: who, which household, what they may do; saved for Settings and for offline use. */
    suspend fun refresh(): ApiResult<Me> {
        if (!settings.value.configured) return ApiResult.Err(ApiError.NotConfigured)
        val r = repo.call { it.me() }
        if (r is ApiResult.Ok) {
            val me = r.value
            store.update { s ->
                s.copy(account = Account(
                    name = me.user?.name, email = me.user?.email, householdName = me.household?.name,
                    roles = me.roles, permissions = me.permissions.toSet(), memberId = me.memberId),
                    // The household the server answered for is the one in use (the session's, when none was picked).
                    householdId = if (s.signedIn) me.household?.id ?: s.householdId else null)
            }
        }
        return r
    }

    /** The switcher: later calls act for [householdId] (the session remembers it too). */
    suspend fun switchHousehold(householdId: Int): ApiResult<Me> {
        when (val r = repo.call { it.pickHousehold(HouseholdPick(householdId)) }) {
            is ApiResult.Err -> return r
            is ApiResult.Ok -> {}
        }
        repo.forgetServerCopies()
        store.update { it.copy(householdId = householdId) }
        settings.first { it.householdId == householdId }
        return refresh()
    }

    /** Ends the session here and on the server (if it can be reached); the household token, if set, takes over. */
    suspend fun signOut() {
        if (settings.value.signedIn) repo.call { it.logout() }
        repo.forgetServerCopies()
        store.update { it.copy(session = "", householdId = null, account = null, pendingVerifier = null) }
        settings.first { !it.signedIn }
        _needsSignIn.value = false
        _status.value = SignInStatus.Idle
        if (settings.value.configured) refresh()
    }

    private fun signInError(e: ApiError): String = when (e) {
        is ApiError.Http -> if (e.code == 400) "That sign-in expired before it finished. Sign in again." else e.userMessage()
        ApiError.Unreachable -> "Can't reach Meal Prep. Check the phone's internet connection and try again."
        else -> e.userMessage()
    }
}
