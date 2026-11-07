package dev.mealprep.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mealprep.app.R
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.Extra
import dev.mealprep.app.data.api.WeekNight
import dev.mealprep.app.ui.cart.ChooserText
import dev.mealprep.app.ui.common.GardenChip

/** What a night's name and store items can do (This week; 2026-11-06). Days are 0 = Sun … 6 = Sat. */
class NightActions(
    val onName: (day: Int, title: String) -> Unit = { _, _ -> },
    val onAddItems: (day: Int) -> Unit = {},
    val onQuantity: (Extra, Int) -> Unit = { _, _ -> },
    val onMove: (Extra, Int?) -> Unit = { _, _ -> },
    val onRemove: (Extra) -> Unit = {},
)

const val NAME_NIGHT = "Name this night"
const val RENAME_NIGHT = "Rename night"
const val CLEAR_NAME = "Clear name"
const val ADD_STORE_ITEMS = "Add store items"
const val WHOLE_WEEK = "For the whole week"

/** ⋮ next to a night: name / rename / clear its name (plan.edit), add store items (cart.build). */
@Composable
fun NightMenu(day: Int, night: WeekNight?, canName: Boolean, canShop: Boolean, onRename: () -> Unit, a: NightActions) {
    if (!canName && !canShop) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "Options for ${Weeks.longDayLabel(day)}")
        }
        DropdownMenu(open, { open = false }) {
            if (canName) DropdownMenuItem({ Text(if (night?.title != null) RENAME_NIGHT else NAME_NIGHT) }, { open = false; onRename() })
            if (canName && night?.title != null) DropdownMenuItem({ Text(CLEAR_NAME) }, { open = false; a.onName(day, "") })
            if (canShop) DropdownMenuItem({ Text(ADD_STORE_ITEMS) }, { open = false; a.onAddItems(day) })
        }
    }
}

/** Under a night's dinners: its name (tap to rename) and its store items (tap one to change it), each 48 dp. */
@Composable
fun NightExtras(day: Int, night: WeekNight?, canName: Boolean, canShop: Boolean, onRename: () -> Unit, onItem: (Extra) -> Unit) {
    night?.title?.let { t ->
        Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.heightIn(min = 48.dp).then(
                if (canName) Modifier.clickable(onClickLabel = "Rename ${Weeks.longDayLabel(day)}", role = Role.Button, onClick = onRename) else Modifier
            ).padding(vertical = 12.dp))
    }
    val items = night?.items.orEmpty()
    if (items.isNotEmpty()) {
        Text("From the store", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEach { e ->
            val label = listOfNotNull(itemName(e), itemCount(e)).joinToString(" ")
            Text(label, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).then(
                    if (canShop) Modifier.clickable(onClickLabel = "Change $label", role = Role.Button) { onItem(e) } else Modifier
                ).padding(vertical = 12.dp))
        }
    }
}

/** Name, rename or clear a night (at most [NIGHT_TITLE_MAX] characters; empty clears it). */
@Composable
fun NameNightDialog(day: Int, current: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current ?: "") }
    val clean = cleanNightTitle(text)
    val tooLong = (clean?.length ?: 0) > NIGHT_TITLE_MAX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current != null) "Rename ${Weeks.longDayLabel(day)}" else "Name ${Weeks.longDayLabel(day)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Name of the night") }, singleLine = true,
                    isError = tooLong, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(if (tooLong) "At most $NIGHT_TITLE_MAX characters (${clean?.length} now)."
                            else "Like Pizza night or Leftovers. Leave it empty to remove the name.")
                    })
                if (current != null) TextButton({ onSave(""); onDismiss() }, Modifier.heightIn(min = 48.dp)) { Text(CLEAR_NAME) }
            }
        },
        confirmButton = { TextButton({ onSave(clean ?: ""); onDismiss() }, enabled = !tooLong, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") } },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

/** A store item of a night: how many (− / +), move it to another night or the whole week, or remove it. The quantity is
 *  saved with Done; a move or Remove right away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NightItemDialog(item: Extra, a: NightActions, onDismiss: () -> Unit) {
    var qty by remember(item.id) { mutableIntStateOf(item.quantity) }
    val p = item.product
    val name = itemName(item)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val fewer = ChooserText.fewer(qty, p)
                    val more = ChooserText.more(qty, p)
                    IconButton({ fewer?.let { qty = it } }, enabled = fewer != null) {
                        Icon(painterResource(R.drawable.ic_remove), contentDescription = ChooserText.fewerLabel(p, name))
                    }
                    Text(ChooserText.quantity(qty, p), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.widthIn(min = 48.dp).semantics { contentDescription = "Quantity ${ChooserText.quantity(qty, p)}" })
                    IconButton({ more?.let { qty = it } }, enabled = more != null) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = ChooserText.moreLabel(p, name))
                    }
                }
                Text("Move to", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { d ->
                        GardenChip(item.day == d, { if (item.day != d) { a.onMove(item, d); onDismiss() } }, Weeks.dayLabel(d),
                            description = Weeks.longDayLabel(d))
                    }
                    GardenChip(item.day == null, { if (item.day != null) { a.onMove(item, null); onDismiss() } }, WHOLE_WEEK)
                }
                TextButton({ a.onRemove(item); onDismiss() }, Modifier.heightIn(min = 48.dp)) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton({ if (qty != item.quantity) a.onQuantity(item, qty); onDismiss() }, Modifier.heightIn(min = 48.dp)) { Text("Done") }
        },
    )
}
