package dev.mealprep.app.ui.setup

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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.ui.common.graphViewModel

@Composable
fun SetupContent(state: SetupState, onUrl: (String) -> Unit, onToken: (String) -> Unit, onSave: () -> Unit, onContinue: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Meal-prep server", style = MaterialTheme.typography.titleLarge)
        Text("Works on home Wi-Fi (or WireGuard). Ask whoever runs the server for the token.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(state.url, onUrl, label = { Text("Server address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.token, onToken, label = { Text("Token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = onSave, enabled = !state.testing) { Text(if (state.testing) "Testing…" else "Save & test") }
        state.result?.let { Text(it, color = if (state.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        if (state.ok) Button(onClick = onContinue) { Text("Continue") }
    }
}

@Composable
fun SetupScreen(graph: AppGraph, onDone: () -> Unit, scrollable: Boolean = true) {
    val vm = graphViewModel { g -> SetupViewModel(g.settingsStore, g.settings, g.repo, g.settings.value) }
    val state by vm.state.collectAsStateWithLifecycle()
    // The form scrolls so the keyboard (adjustResize) can't hide Continue; Settings supplies its own scroll.
    Box(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier) {
        SetupContent(state, vm::setUrl, vm::setToken, { vm.saveAndTest() }, onDone)
    }
}

/** Settings = the setup form plus sections later tasks add (Loblaws, notifications). */
@Composable
fun SettingsScreen(graph: AppGraph, onDone: () -> Unit, extra: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SetupScreen(graph, onDone = onDone, scrollable = false)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { extra() }
        TextButton(onClick = onDone, modifier = Modifier.padding(16.dp)) { Text("Done") }
    }
}
