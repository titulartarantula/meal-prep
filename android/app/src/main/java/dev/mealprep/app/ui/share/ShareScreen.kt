package dev.mealprep.app.ui.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import dev.mealprep.app.ui.camera.rememberDeleteWithUndo
import dev.mealprep.app.ui.common.BackTopBar
import java.io.File
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.BookSuggestion
import dev.mealprep.app.core.ShareInput
import dev.mealprep.app.core.Sources
import dev.mealprep.app.ui.camera.PageStrip
import dev.mealprep.app.ui.common.BookFields
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OtherFields
import dev.mealprep.app.ui.common.SourceKindChips
import dev.mealprep.app.ui.common.WhereFromTitle
import kotlinx.coroutines.launch
import dev.mealprep.app.ui.common.graphViewModel

@Composable
fun ShareContent(
    state: ShareState, onConfirm: () -> Unit, onBack: () -> Unit, onSetup: () -> Unit,
    onTitle: (String) -> Unit = {}, onMove: (Int, Int) -> Unit = { _, _ -> }, onRemove: (Int) -> Unit = {},
    onBook: (String) -> Unit = {}, onPage: (String) -> Unit = {}, onPickBook: (BookSuggestion) -> Unit = {},
    onKind: (String) -> Unit = {}, onOtherName: (String) -> Unit = {}, onNote: (String) -> Unit = {},
    onRestore: (Int, File) -> Unit = { _, _ -> },
) {
    val snackbar = remember { SnackbarHostState() }
    val delete = rememberDeleteWithUndo(state.pages, snackbar, onRemove, onRestore)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar(if (state.isPhotos && state.kind == Sources.BOOK) "Save a cookbook recipe" else "Save a recipe", onBack)
            ShareBody(state, onConfirm, onSetup, onTitle, onMove, delete, onBook, onPage, onPickBook, onKind, onOtherName, onNote)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ColumnScope.ShareBody(
    state: ShareState, onConfirm: () -> Unit, onSetup: () -> Unit, onTitle: (String) -> Unit, onMove: (Int, Int) -> Unit,
    onRemove: (Int) -> Unit, onBook: (String) -> Unit, onPage: (String) -> Unit, onPickBook: (BookSuggestion) -> Unit,
    onKind: (String) -> Unit, onOtherName: (String) -> Unit, onNote: (String) -> Unit,
) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    "use ‹ › if they're out of order.")
                PageStrip(state.pages, retakeEnabled = false, onRetake = null, onRemove = onRemove, onMove = onMove)
                OutlinedTextField(state.title, onTitle, label = { Text("Title (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                WhereFromTitle()
                SourceKindChips(state.kind, onKind)
                if (state.kind == Sources.OTHER) OtherFields(state.otherName, onOtherName, state.note, onNote, state.others, nameMissing = state.nameMissing)
                else BookFields(state.book, onBook, state.page, onPage, state.suggestions, onPickBook)
            }
        }
        MessageText(state.message)
        if (state.input is ShareInput.NytLink || (state.isPhotos && state.pages.pages.isNotEmpty())) {
            Text("It goes into Recipes. Add it to a week from there when you plan.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onConfirm, enabled = state.canSave, modifier = Modifier.fillMaxWidth()) { Text(SAVE) }
            if (state.isPhotos) Text("Reading pages takes about a minute. You can leave the app; it will let you know.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

const val SAVE = "Save to Recipes"

@Composable
fun ShareScreen(graph: AppGraph, onQueued: () -> Unit, onBack: () -> Unit, onSetup: () -> Unit) {
    val vm = graphViewModel { g ->
        ShareViewModel(g.repo, g.imports, g.pages, g.copyPage, { g.settings.value.configured },
            lastBook = { g.settings.value.lastBook },
            rememberBook = { b -> g.scope.launch { g.settingsStore.update { it.copy(lastBook = b) } } })
    }
    val pending by graph.pendingShare.collectAsStateWithLifecycle()
    LaunchedEffect(pending) { pending?.let { vm.start(it); graph.pendingShare.value = null } }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.queued) { if (state.queued) onQueued() }
    ShareContent(state, { vm.confirm() }, onBack, onSetup, vm::setTitle, vm::movePage, vm::removePage, vm::setBook, vm::setPage,
        vm::pickBook, vm::setKind, vm::setOtherName, vm::setNote, onRestore = vm::restorePage)
}
