package dev.mealprep.app.ui.fridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mealprep.app.R
import dev.mealprep.app.data.api.Proposal
import dev.mealprep.app.graph
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomActionBar
import dev.mealprep.app.ui.common.GardenChip
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.announced
import androidx.compose.ui.platform.LocalContext

const val REVIEW_TITLE = "Changes from your photos"
const val DISCARD_ALL = "Discard all"
const val TAKE_NEW_PHOTOS = "Take new photos"

data class ReviewActions(
    val onBack: () -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onEdit: (String, LineEdit) -> Unit = { _, _ -> },
    val onSave: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onNewPhotos: () -> Unit = {},
    val onMessageSeen: () -> Unit = {},
)

@Composable
fun FridgeReviewScreen(readId: Int, onDone: (String) -> Unit, onBack: () -> Unit, onNewPhotos: () -> Unit) {
    val g = LocalContext.current.graph
    val vm: FridgeReviewViewModel = viewModel(key = "fridge-review-$readId", factory = viewModelFactory {
        initializer { FridgeReviewViewModel(g.repo, readId, createSavedStateHandle(), resend = { dir -> g.imports.enqueueFridge(dir) }) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { state.done?.let(onDone) }
    ReviewContent(state, ReviewActions(onBack, vm::toggle, vm::edit, vm::save, vm::discard, vm::retry, onNewPhotos, vm::clearMessage))
}

@Composable
fun ReviewContent(state: ReviewState, a: ReviewActions) {
    var editing by remember { mutableStateOf<Proposal?>(null) }
    var menu by remember { mutableStateOf(false) }
    val read = state.read
    Column(Modifier.fillMaxSize()) {
        BackTopBar(REVIEW_TITLE, a.onBack, subtitle = read?.let { "${it.photoCount} photo${if (it.photoCount == 1) "" else "s"}" }) {
            if (read?.status == "ready") Box {
                IconButton({ menu = true }, Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options")
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(DISCARD_ALL) }, { menu = false; a.onDiscard() }, enabled = !state.saving)
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            OfflineBanner(state.offlineSince)
            MessageText(state.error)
            MessageText(state.message)
            if (state.loading || state.saving || read?.status == "reading") LinearProgressIndicator(Modifier.fillMaxWidth())
            when (read?.status) {
                "reading" -> Text(READING_PHOTOS, Modifier.padding(vertical = 12.dp).announced())
                "failed" -> Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(read.error ?: "Couldn't read the fridge in those photos.", Modifier.announced())
                    if (state.canRetry) Button(a.onRetry, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                    OutlinedButton(a.onNewPhotos, Modifier.heightIn(min = 48.dp)) { Text(TAKE_NEW_PHOTOS) }
                }
                "applied", "dismissed" -> Text(if (read.status == "applied") ReviewLogic.resultText(read.result)
                    else FridgeReviewViewModel.DISCARDED, Modifier.padding(vertical = 12.dp))
                "ready" -> LazyColumn(Modifier.weight(1f)) {
                    if (read.proposals.isEmpty()) item { Text("Nothing to change: the photos match the list.", Modifier.padding(vertical = 12.dp)) }
                    ReviewLogic.SECTIONS.forEach { (kind, heading) ->
                        val lines = read.proposals.filter { it.kind == kind }
                        if (lines.isNotEmpty()) {
                            item(key = "h-$kind") {
                                Text("$heading (${lines.size})", style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
                            }
                            items(lines, key = { it.pid }) { p ->
                                ReviewRow(p, state.edits[p.pid], p.pid in state.ticks, enabled = !state.saving,
                                    onToggle = { a.onToggle(p.pid) }, onEdit = { editing = p })
                            }
                        }
                    }
                }
            }
        }
        if (read?.status == "ready") {
            val n = state.ticks.count { pid -> read.proposals.any { it.pid == pid } }
            BottomActionBar {
                Button(a.onSave, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = n > 0 && !state.saving) {
                    Text(if (state.saving) "Saving…" else ReviewLogic.saveLabel(n))
                }
            }
        }
    }
    editing?.let { p ->
        EditLineDialog(p, state.edits[p.pid], onSave = { e -> a.onEdit(p.pid, e); editing = null }, onDismiss = { editing = null })
    }
}

/** One proposal: a whole-row checkbox with the change in words; Edit for the name (new) or the amount. */
@Composable
private fun ReviewRow(p: Proposal, e: LineEdit?, ticked: Boolean, enabled: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(ticked, enabled = enabled, role = Role.Checkbox) { onToggle() },
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(ticked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
            Text(ReviewLogic.text(p, e), Modifier.padding(vertical = 8.dp))
        }
        if (p.kind != "not_seen") TextButton(onEdit, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Edit") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditLineDialog(p: Proposal, e: LineEdit?, onSave: (LineEdit) -> Unit, onDismiss: () -> Unit) {
    val start = e ?: LineEdit(qty = p.estimate?.qty ?: p.current?.qty, unit = p.estimate?.unit ?: p.current?.unit)
    var name by remember { mutableStateOf(e?.name ?: p.name) }
    var amount by remember { mutableStateOf(start.qty?.let(FridgeForm::plain) ?: "") }
    var unit by remember { mutableStateOf(start.unit ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${p.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.kind == "new") OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(amount, { amount = it }, label = { Text("How many / how much") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FridgeLogic.UNITS.forEach { (label, value) ->
                        GardenChip((value ?: "") == unit.trim().lowercase(), { unit = value ?: "" }, label, Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedTextField(unit, { unit = it }, label = { Text("Other unit (optional)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                MessageText(error)
            }
        },
        confirmButton = {
            TextButton({
                val q = FridgeLogic.parseAmount(amount)
                when {
                    q.isFailure -> error = q.exceptionOrNull()?.message
                    p.kind == "new" && name.isBlank() -> error = "Give it a name."
                    else -> {
                        val amountEdited = q.getOrNull() != (p.estimate?.qty ?: p.current?.qty) ||
                            unit.trim().ifBlank { null } != (p.estimate?.unit ?: p.current?.unit) || e?.amountEdited == true
                        onSave(LineEdit(name.trim().takeIf { p.kind == "new" && it != p.name }, q.getOrNull(),
                            unit.trim().ifBlank { null }, amountEdited))
                    }
                }
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
