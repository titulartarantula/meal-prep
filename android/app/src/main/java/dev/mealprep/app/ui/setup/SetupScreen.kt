package dev.mealprep.app.ui.setup

import dev.mealprep.app.ui.common.announced
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import dev.mealprep.app.R
import dev.mealprep.app.data.api.ApiError
import java.time.LocalDate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.graphViewModel

@Composable
fun SetupContent(state: SetupState, onUrl: (String) -> Unit, onToken: (String) -> Unit, onSave: () -> Unit, onContinue: () -> Unit,
                 /** First run only: Settings has its own Done. */
                 showContinue: Boolean = true,
                 /** First run: the screen's title. Settings has its own section heading. */
                 showTitle: Boolean = true) {
    var tokenShown by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(if (showTitle) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) Text("Meal-prep server", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text("Works on home Wi-Fi. Away from home, turn on the household VPN (WireGuard) first. Ask whoever runs the server " +
            "for the token.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(state.url, onUrl, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next))
        OutlinedTextField(state.token, onToken, label = { Text("Token") }, singleLine = true,
            visualTransformation = if (tokenShown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!state.testing) onSave() }),
            trailingIcon = {
                IconButton({ tokenShown = !tokenShown }) {
                    Icon(painterResource(if (tokenShown) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                        contentDescription = if (tokenShown) "Hide token" else "Show token")
                }
            },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = onSave, enabled = !state.testing) { Text(if (state.testing) "Testing…" else "Save & test") }
        state.result?.let { Text(it, Modifier.announced(), color = if (state.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        if (state.ok && showContinue) Button(onClick = onContinue) { Text("Continue") }
    }
}

@Composable
fun SetupScreen(graph: AppGraph, onDone: () -> Unit, scrollable: Boolean = true, showContinue: Boolean = true, showTitle: Boolean = true) {
    val vm = graphViewModel { g -> SetupViewModel(g.settingsStore, g.settings, g.repo, g.settings.value) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The form scrolls so the keyboard (adjustResize) can't hide Continue; Settings supplies its own scroll.
    Box(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
        SetupContent(state, vm::setUrl, vm::setToken, { vm.saveAndTest() }, onDone, showContinue, showTitle)
    }
}

/** Settings: what people change first ([extra]: notifications, Loblaws), then the server, folded away once it is set
 *  up (nobody should need it after setup). */
@Composable
fun SettingsScreen(graph: AppGraph, onDone: () -> Unit, extra: @Composable ColumnScope.() -> Unit = {}) {
    val s by graph.settings.collectAsStateWithLifecycle()
    // The same check as Save & test's second step: the token works too, not just the address.
    val status by produceState("Checking…") { value = serverStatus(graph.repo.weeks(LocalDate.now(), 1).error) }
    var serverOpen by rememberSaveable { mutableStateOf(false) }
    SettingsContent(onBack = onDone, extra = extra, server = {
        ServerSection(s.serverUrl, s.configured, status, serverOpen || !s.configured, onOpen = { serverOpen = true }) {
            SetupScreen(graph, onDone = onDone, scrollable = false, showContinue = false, showTitle = false)
        }
    })
}

@Composable
fun SettingsContent(onBack: () -> Unit, extra: @Composable ColumnScope.() -> Unit, server: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Settings", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            extra()
            server()
        }
    }
}

internal fun serverStatus(e: ApiError?): String = when (e) {
    null -> "Connected ✓"
    ApiError.Unauthorized -> "It rejected the token. Tap Change."
    else -> "Can't reach it from here right now."
}

/** "Meal-prep server" heading; once set up, one line ("Connected ✓", the address) with Change, which opens the form. */
@Composable
fun ServerSection(url: String, configured: Boolean, status: String, open: Boolean, onOpen: () -> Unit, form: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Meal-prep server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (open) form()
        else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status)
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (configured) TextButton(onOpen) { Text("Change") }
        }
    }
}
