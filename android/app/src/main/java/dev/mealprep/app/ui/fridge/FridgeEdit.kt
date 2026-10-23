package dev.mealprep.app.ui.fridge

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.ui.common.GardenChip
import dev.mealprep.app.ui.common.MessageText
import java.time.LocalDate

const val FRIDGE_SAVE = "Save"
const val FRIDGE_REMOVE = "Remove from the fridge"

/** Add or edit one item: name (with the shopping list's names as suggestions), amount, unit, Use soon, use-by. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FridgeEditDialog(
    f: FridgeForm, names: List<String>, message: String?, today: LocalDate,
    onChange: (FridgeForm) -> Unit, onSave: () -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (f.id == null) "Add to the fridge" else "Edit ${f.name.ifBlank { "item" }}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.name, { onChange(f.copy(name = it, fromList = false)) }, label = { Text("Name") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                // Names the recipes use, so the item matches the shopping list.
                names.filterNot { it.equals(f.name.trim(), ignoreCase = true) }.take(5).forEach { n ->
                    TextButton({ onChange(f.copy(name = n, fromList = true)) }, Modifier.heightIn(min = 48.dp)) { Text(n) }
                }
                OutlinedTextField(f.amount, { onChange(f.copy(amount = it)) }, label = { Text("How many / how much") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text("Leave empty for “some”.") }, modifier = Modifier.fillMaxWidth())
                Text("Unit", style = MaterialTheme.typography.labelLarge)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FridgeLogic.UNITS.forEach { (label, value) ->
                        GardenChip((value ?: "") == f.unit.trim().lowercase(), { onChange(f.copy(unit = value ?: "")) }, label,
                            Modifier.heightIn(min = 48.dp))
                    }
                }
                OutlinedTextField(f.unit, { onChange(f.copy(unit = it)) }, label = { Text("Other unit (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(f.useSoon, role = Role.Checkbox) { onChange(f.copy(useSoon = it)) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.useSoon, onCheckedChange = null)
                    Text("Use soon", Modifier.padding(start = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.useBy?.let { "Use by ${Weeks.shortDate(it)}" } ?: "No use-by date", Modifier.weight(1f))
                    TextButton({
                        val d = f.useBy ?: today
                        DatePickerDialog(ctx, { _, y, m, day -> onChange(f.copy(useBy = LocalDate.of(y, m + 1, day))) },
                            d.year, d.monthValue - 1, d.dayOfMonth).show()
                    }, Modifier.heightIn(min = 48.dp)) { Text(if (f.useBy == null) "Set use-by" else "Change") }
                    if (f.useBy != null) TextButton({ onChange(f.copy(useBy = null)) }, Modifier.heightIn(min = 48.dp)) { Text("Clear") }
                }
                MessageText(message)
                onRemove?.let { TextButton(it, Modifier.heightIn(min = 48.dp)) { Text(FRIDGE_REMOVE, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onSave) { Text(FRIDGE_SAVE) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
