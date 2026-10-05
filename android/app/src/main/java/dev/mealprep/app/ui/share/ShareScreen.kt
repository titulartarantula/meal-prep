package dev.mealprep.app.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.ui.camera.PageStrip
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.WeekPicker
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate

@Composable
fun ShareContent(
    state: ShareState, onSelect: (LocalDate) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit, onSetup: () -> Unit,
    onTitle: (String) -> Unit = {}, onMove: (Int, Int) -> Unit = { _, _ -> }, onRemove: (Int) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.isPhotos) "Add a cookbook recipe" else "Add a recipe", style = MaterialTheme.typography.titleLarge)
        (state.input as? ShareInput.NytLink)?.let { Text("NYT Cooking · ${it.url.substringAfterLast('/')}") }
        if (!state.configured) {
            Text("Connect to the meal-prep server first.")
            Button(onClick = onSetup) { Text("Set up") }
            return@Column
        }
        if (state.isPhotos) {
            if (state.copying) {
                Text("Getting the photos ready…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (state.pages.pages.isNotEmpty()) {
                Text(if (state.pages.pages.size == 1) "1 page" else "${state.pages.pages.size} pages, in reading order — " +
                    "use ◀ ▶ if they're out of order.")
                PageStrip(state.pages, retakeEnabled = false, onRetake = null, onRemove = onRemove, onMove = onMove)
                OutlinedTextField(state.title, onTitle, label = { Text("Title (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        MessageText(state.message)
        if (state.input is ShareInput.NytLink || (state.isPhotos && state.pages.pages.isNotEmpty())) {
            Text("Which week is it for?", style = MaterialTheme.typography.titleMedium)
            WeekPicker(state.options, state.selected, onSelect)
            Button(onClick = onConfirm, enabled = state.canConfirm) { Text(if (state.isPhotos) "Read recipe" else "Add to week") }
            if (state.isPhotos) Text("Reading pages takes about a minute. You can leave the app; it will let you know.",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onCancel) { Text(if (state.canConfirm || state.copying) "Cancel" else "Close") }
    }
}

@Composable
fun ShareScreen(graph: AppGraph, onQueued: (LocalDate) -> Unit, onCancel: () -> Unit, onSetup: () -> Unit) {
    val vm = graphViewModel { g -> ShareViewModel(g.repo, g.imports, g.pages, g.copyPage, { g.settings.value.configured }) }
    val pending by graph.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { vm.start(it); graph.pendingShare.value = null } }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.queued) { if (state.queued) onQueued(state.selected!!) }
    ShareContent(state, vm::select, { vm.confirm() }, onCancel, onSetup, vm::setTitle, vm::movePage, vm::removePage)
}
