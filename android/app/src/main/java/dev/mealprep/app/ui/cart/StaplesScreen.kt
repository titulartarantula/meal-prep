package dev.mealprep.app.ui.cart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import dev.mealprep.app.R
import dev.mealprep.app.data.api.Staple
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomAction
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.graphViewModel

@Composable
fun StaplesScreen(onBack: () -> Unit) {
    val vm = graphViewModel { g -> StaplesViewModel(g.repo) }
    val state by vm.state.collectAsStateWithLifecycle()
    StaplesContent(state, onSave = vm::save, onDelete = vm::delete, onMove = vm::move, onBack = onBack, onMessageSeen = vm::clearMessage)
}

@Composable
fun StaplesContent(
    state: StaplesState,
    onSave: (StapleForm) -> Boolean,
    onDelete: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onBack: () -> Unit,
    onMessageSeen: () -> Unit = {},
) {
    var form by remember { mutableStateOf<StapleForm?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Staples", onBack)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Staples are at the top of the shopping list. “Every week” ones start ticked; untick what you don't need.",
                style = MaterialTheme.typography.bodySmall)
            OfflineBanner(state.offlineSince)
            MessageText(state.error)
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loading && state.staples.isEmpty() && state.error == null) Text("No staples yet.", Modifier.padding(vertical = 8.dp))
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(state.staples, key = { _, s -> s.id }) { i, s ->
                    StapleRow(s, first = i == 0, last = i == state.staples.lastIndex, enabled = !state.busy,
                        onEdit = { form = StapleForm.of(s) }, onMove = { by -> onMove(s.id, by) })
                    HorizontalDivider()
                }
            }
        }
        BottomAction("Add a staple", { form = StapleForm() })
    }
    form?.let { f ->
        StapleDialog(f, state.message, onChange = { form = it; onMessageSeen() },
            onSave = { if (onSave(f)) form = null },
            onDelete = f.id?.let { id -> { onDelete(id); form = null } },
            onDismiss = { form = null; onMessageSeen() })
    }
    // A save that failed on the server (after the dialog closed) is said below the title.
    if (form == null) state.message?.let { m ->
        AlertDialog(onDismissRequest = onMessageSeen, text = { Text(m) }, confirmButton = { TextButton(onMessageSeen) { Text("OK") } })
    }
}

@Composable
private fun StapleRow(s: Staple, first: Boolean, last: Boolean, enabled: Boolean, onEdit: () -> Unit, onMove: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        // Tapping the name opens the editor (rename, amount, every week, delete).
        Column(Modifier.weight(1f).clickable(enabled = enabled, onClickLabel = "Edit ${s.name}", role = Role.Button, onClick = onEdit)
            .padding(vertical = 8.dp)) {
            Text("${s.name} · ${stapleAmount(s)}")
            Text(if (s.weekly) "Every week (starts ticked)" else "Only when ticked", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton({ onMove(-1) }, enabled = enabled && !first) {
            Icon(painterResource(R.drawable.ic_arrow_up), contentDescription = "Move ${s.name} up")
        }
        IconButton({ onMove(1) }, enabled = enabled && !last) {
            Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = "Move ${s.name} down")
        }
    }
}

@Composable
private fun StapleDialog(
    f: StapleForm, message: String?, onChange: (StapleForm) -> Unit, onSave: () -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (f.id == null) "Add a staple" else "Edit staple") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.name, { onChange(f.copy(name = it)) }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.amount, { onChange(f.copy(amount = it)) }, label = { Text("Amount") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.unit, { onChange(f.copy(unit = it)) }, label = { Text("Unit (optional)") }, singleLine = true,
                    supportingText = { Text("Leave empty to count packs: 1 = one carton, bag or box. Or g, kg, ml, L, can, bottle…") },
                    modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(f.weekly, role = Role.Checkbox) { onChange(f.copy(weekly = it)) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.weekly, onCheckedChange = null)
                    Text("Every week (starts ticked on the list)", Modifier.padding(start = 12.dp))
                }
                MessageText(message)
                onDelete?.let { TextButton(it) { Text("Delete this staple", color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onSave) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
