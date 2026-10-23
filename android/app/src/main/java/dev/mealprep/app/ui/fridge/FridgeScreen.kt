package dev.mealprep.app.ui.fridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.R
import dev.mealprep.app.data.api.FridgeItem
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.ui.common.AdviceText
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomActionBar
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.announced
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate

const val SUGGEST_DINNERS = "Suggest dinners"
const val TAKE_A_PHOTO = "Take a photo"
const val STILL_RIGHT = "Still right"
const val READING_PHOTOS = "Reading your fridge photos…"

/** What the Fridge screen can do (FridgeScreenTest drives these). */
data class FridgeActions(
    val onBack: () -> Unit = {},
    val onSave: (FridgeForm) -> Boolean = { true },
    val onConfirm: (Int) -> Unit = {},
    val onRemove: (FridgeItem) -> Unit = {},
    val onUndo: () -> Unit = {},
    val onCommit: () -> Unit = {},
    val onTyped: (String) -> Unit = {},
    val onSuggest: () -> Unit = {},
    val onPhoto: () -> Unit = {},
    val onReview: (Int) -> Unit = {},
    val onMessageSeen: () -> Unit = {},
)

@Composable
fun FridgeScreen(onBack: () -> Unit, onSuggest: () -> Unit, onPhoto: () -> Unit, onReview: (Int) -> Unit) {
    val vm = graphViewModel { g -> FridgeViewModel(g.repo) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Back from a review, or the other phone changed it: read it again.
    LifecycleResumeEffect(Unit) { vm.load(); onPauseOrDispose { } }
    // Leaving the screen makes a removal waiting for its Undo final (the same rule as Remove from week).
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.commitRemove() }
    FridgeContent(state, vm.today(), FridgeActions(onBack, vm::save, vm::confirm, vm::remove, vm::undoRemove, vm::commitRemove,
        vm::typed, onSuggest, onPhoto, onReview, vm::clearMessage))
}

@Composable
fun FridgeContent(state: FridgeState, today: LocalDate, a: FridgeActions) {
    var form by remember { mutableStateOf<FridgeForm?>(null) }
    var typed by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.removing) {
        val i = state.removing ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Took ${i.name} off the list", actionLabel = "Undo", withDismissAction = true,
            duration = SnackbarDuration.Long)
        if (r == SnackbarResult.ActionPerformed) a.onUndo() else a.onCommit()
    }
    fun open(f: FridgeForm) { form = f; typed = ""; a.onTyped(f.name) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackTopBar("In the fridge", a.onBack)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                OfflineBanner(state.offlineSince)
                MessageText(state.error)
                if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f)) {
                    state.read?.let { r -> item(key = "read") { ReadCard(r, a.onReview) } }
                    item(key = "add") {
                        OutlinedTextField(typed, { typed = it; a.onTyped(it) }, label = { Text("Add to the fridge") },
                            singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) open(FridgeForm(name = typed.trim())) }),
                            trailingIcon = {
                                IconButton({ open(FridgeForm(name = typed.trim())) }) {
                                    Icon(painterResource(R.drawable.ic_add), contentDescription = "Add ${typed.trim()}".trim())
                                }
                            })
                    }
                    if (typed.isNotBlank() && form == null) items(state.names.take(6), key = { "name-$it" }) { n ->
                        Text(n, Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Add $n", role = Role.Button) { open(FridgeForm(name = n, fromList = true)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                    if (!state.loading && state.shown.isEmpty() && state.error == null) item(key = "empty") {
                        Text(FridgeLogic.EMPTY, Modifier.padding(vertical = 12.dp))
                    }
                    items(state.shown, key = { it.id }) { i ->
                        ItemRow(i, today, enabled = !state.busy, onEdit = { open(FridgeForm.of(i)) },
                            onConfirm = { a.onConfirm(i.id) }, onRemove = { a.onRemove(i) })
                        HorizontalDivider()
                    }
                }
            }
            BottomActionBar {
                Button(a.onSuggest, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.shown.isNotEmpty()) { Text(SUGGEST_DINNERS) }
                OutlinedButton(a.onPhoto, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) { Text(TAKE_A_PHOTO) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
    form?.let { f ->
        val item = state.items.firstOrNull { it.id == f.id }
        FridgeEditDialog(f, state.names, state.message, today,
            onChange = { n -> if (n.name != f.name) a.onTyped(n.name); form = n; a.onMessageSeen() },
            onSave = { if (a.onSave(f)) form = null },
            onRemove = item?.let { i -> { a.onRemove(i); form = null } },
            onDismiss = { form = null; a.onMessageSeen() })
    }
    // "Already in the fridge: 6 Eggs" — open that one instead.
    if (form == null) state.exists?.let { e ->
        val there = state.items.firstOrNull { it.id == e.id }
        AlertDialog(onDismissRequest = a.onMessageSeen, text = { Text(e.detail) },
            confirmButton = { if (there != null) TextButton({ a.onMessageSeen(); open(FridgeForm.of(there)) }) { Text("Edit it") } },
            dismissButton = { TextButton(a.onMessageSeen) { Text(if (there != null) "Cancel" else "OK") } })
    }
    // A change that failed on the server after the dialog closed.
    if (form == null && state.exists == null) state.message?.let { m ->
        AlertDialog(onDismissRequest = a.onMessageSeen, text = { Text(m) }, confirmButton = { TextButton(a.onMessageSeen) { Text("OK") } })
    }
}

/** "Reading your fridge photos…" while a read is out; "Review 5 changes from your photos" when it is ready. */
@Composable
private fun ReadCard(r: FridgeRead, onReview: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (r.status == "reading") Text(READING_PHOTOS, Modifier.padding(12.dp).announced())
        else Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(reviewText(r), Modifier.weight(1f).announced())
            TextButton({ onReview(r.id) }, Modifier.heightIn(min = 48.dp)) { Text("Review") }
        }
    }
}

internal fun reviewText(r: FridgeRead): String {
    val n = r.proposals.size
    return "Review $n change${if (n == 1) "" else "s"} from your photos"
}

/** One item: the whole row opens its editor; ⋮ has Still right and Remove. TalkBack reads it as one sentence. */
@Composable
private fun ItemRow(i: FridgeItem, today: LocalDate, enabled: Boolean, onEdit: () -> Unit, onConfirm: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val said = FridgeLogic.description(i, today)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clickable(enabled = enabled, onClickLabel = "Edit ${i.name}", role = Role.Button, onClick = onEdit)
            .padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = said },
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(FridgeLogic.title(i), style = MaterialTheme.typography.bodyLarge)
            FridgeLogic.details(i, today).takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (FridgeLogic.stale(i)) AdviceText(FridgeLogic.asOf(i, today))
        }
        Box {
            IconButton({ menu = true }, Modifier.size(48.dp), enabled = enabled) {
                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More for ${i.name}")
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(STILL_RIGHT) }, { menu = false; onConfirm() })
                DropdownMenuItem({ Text("Remove") }, { menu = false; onRemove() })
            }
        }
    }
}
