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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.ui.camera.PageStrip
import dev.mealprep.app.ui.common.BookFields
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.WhichBookTitle
import kotlinx.coroutines.launch
import dev.mealprep.app.ui.common.graphViewModel

@Composable
fun ShareContent(
    state: ShareState, onConfirm: () -> Unit, onCancel: () -> Unit, onSetup: () -> Unit,
    onTitle: (String) -> Unit = {}, onMove: (Int, Int) -> Unit = { _, _ -> }, onRemove: (Int) -> Unit = {},
    onBook: (String) -> Unit = {}, onPage: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (state.isPhotos) "Save a cookbook recipe" else "Save a recipe", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
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
                WhichBookTitle()
                BookFields(state.book, onBook, state.page, onPage, state.books)
            }
        }
        MessageText(state.message)
        if (state.input is ShareInput.NytLink || (state.isPhotos && state.pages.pages.isNotEmpty())) {
            Text("It goes into Recipes. Add it to a week from there when you plan.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConfirm, enabled = state.canConfirm, modifier = Modifier.fillMaxWidth()) { Text(SAVE) }
            if (state.isPhotos) Text("Reading pages takes about a minute. You can leave the app; it will let you know.",
                style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onCancel) { Text(if (state.canConfirm || state.copying) "Cancel" else "Close") }
    }
}

const val SAVE = "Save to Recipes"

@Composable
fun ShareScreen(graph: AppGraph, onQueued: () -> Unit, onCancel: () -> Unit, onSetup: () -> Unit) {
    val vm = graphViewModel { g ->
        ShareViewModel(g.repo, g.imports, g.pages, g.copyPage, { g.settings.value.configured },
            lastBook = { g.settings.value.lastBook },
            rememberBook = { b -> g.scope.launch { g.settingsStore.update { it.copy(lastBook = b) } } })
    }
    val pending by graph.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { vm.start(it); graph.pendingShare.value = null } }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.queued) { if (state.queued) onQueued() }
    ShareContent(state, { vm.confirm() }, onCancel, onSetup, vm::setTitle, vm::movePage, vm::removePage, vm::setBook, vm::setPage)
}
