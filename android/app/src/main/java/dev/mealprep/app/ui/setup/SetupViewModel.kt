package dev.mealprep.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.data.settings.Settings
import dev.mealprep.app.data.settings.SettingsStore
import dev.mealprep.app.data.settings.normalizeServerUrl
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupState(
    val url: String = Settings.DEFAULT_SERVER_URL, val token: String = "",
    val testing: Boolean = false, val result: String? = null, val ok: Boolean = false,
)

class SetupViewModel(
    private val store: SettingsStore,
    private val settings: StateFlow<Settings>,
    private val repo: Repository,
    initial: Settings,
) : ViewModel() {
    private val _state = MutableStateFlow(SetupState(initial.serverUrl, initial.token))
    val state = _state.asStateFlow()

    fun setUrl(v: String) = _state.update { it.copy(url = v, result = null, ok = false) }
    fun setToken(v: String) = _state.update { it.copy(token = v, result = null, ok = false) }

    fun saveAndTest() = viewModelScope.launch {
        val url = normalizeServerUrl(_state.value.url); val token = _state.value.token.trim()
        _state.update { it.copy(url = url, token = token, testing = true, result = null, ok = false) }
        store.update { it.copy(serverUrl = url, token = token) }
        settings.first { it.serverUrl == url && it.token == token }   // the API provider reads this flow
        // /health is open, so it separates "no server" from "token rejected".
        val (msg, ok) = when (val h = repo.health()) {
            is ApiResult.Err -> h.error.userMessage() to false
            is ApiResult.Ok -> when (val e = repo.weeks(LocalDate.now(), 1).error) {
                null -> "Connected." to true
                ApiError.Unauthorized -> "Server found, but it rejected the token." to false
                else -> e.userMessage() to false
            }
        }
        _state.update { it.copy(testing = false, result = msg, ok = ok) }
    }
}
