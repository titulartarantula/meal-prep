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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.graphViewModel
import java.util.Locale

internal fun price(p: Double?) = p?.let { String.format(Locale.US, "$%.2f", it) } ?: ""
internal fun productLine(p: Product) =
    listOfNotNull(p.brand, p.name, p.packageSize).joinToString(" · ") + (p.price?.let { "  " + price(it) } ?: "")
private val SOURCE = mapOf("user" to "Your pick", "memory" to "Remembered", "ai" to "Suggested", "none" to "No match")

/** Callbacks of the draft screen (one object so the stateless content stays readable). */
class DraftActions(
    val onQty: (DraftLine, Int) -> Unit = { _, _ -> },
    val onRemove: (DraftLine, Boolean) -> Unit = { _, _ -> },
    val onSwap: (DraftLine) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onChoose: (Product) -> Unit = {},
    val onCloseSwap: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onLoblaws: (String) -> Unit = {},
    val onRebuild: (List<String>) -> Unit = {},
    val onReload: () -> Unit = {},
)

@Composable
fun DraftContent(s: DraftState, a: DraftActions) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val d = s.draft
        Text("Cart", style = MaterialTheme.typography.titleLarge)
        if (d == null || d.status == "building") {
            if (d != null || s.loading) {
                Text(d?.let { "Finding products… ${it.progress.done} of ${it.progress.total}" } ?: "Loading…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("You can leave this screen — you'll get a notification when it's ready.", style = MaterialTheme.typography.bodySmall)
            }
        }
        MessageText(s.error)
        if (d == null && !s.loading) TextButton(a.onReload) { Text("Try again") }
        if (d != null && d.status != "building") {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(d.lines, key = { it.id }) { line ->
                    LineCard(line, editable = s.editable, busy = s.busyLine == line.id,
                        onQty = { a.onQty(line, it) }, onRemove = { a.onRemove(line, !line.removed) }, onSwap = { a.onSwap(line) })
                }
            }
            Text("About ${price(s.total)} · ${s.itemCount} ${if (s.itemCount == 1) "item" else "items"}",
                style = MaterialTheme.typography.titleMedium)
            when (d.status) {
                "ready" -> Button(onClick = a.onSend, enabled = s.canSend, modifier = Modifier.fillMaxWidth()) {
                    Text(if (s.sending) "Sending…" else "Send to Loblaws")
                }
                "sent" -> d.pcxCartId?.let { cart ->
                    Button(onClick = { a.onLoblaws(cart) }, modifier = Modifier.fillMaxWidth()) { Text("Open in Loblaws") }
                }
                else -> {
                    Text("This cart couldn't be built.")
                    Button(onClick = { a.onRebuild(d.weeks) }, modifier = Modifier.fillMaxWidth()) { Text("Build it again") }
                }
            }
        }
    }
    s.swap?.let { swap -> SwapDialog(swap, a.onSearch, a.onChoose, a.onCloseSwap) }
}

/** What the recipes need, under the item name: "Need ⅚ cup · warmed"; null when there's nothing to say. */
internal fun needText(line: DraftLine): String? =
    listOfNotNull(amountText(line.qty, line.unit)?.let { "Need $it" }, line.prep).joinToString(" · ").ifEmpty { null }

@Composable
private fun LineCard(line: DraftLine, editable: Boolean, busy: Boolean, onQty: (Int) -> Unit, onRemove: () -> Unit, onSwap: () -> Unit) {
    val q = line.quantity ?: 0
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(line.name, style = MaterialTheme.typography.titleSmall,
                textDecoration = if (line.removed) TextDecoration.LineThrough else null)
            needText(line)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val forWhat = if (line.recipes.isEmpty()) "" else "for ${line.recipes.joinToString(", ")} · "
            Text(forWhat + (SOURCE[line.source] ?: line.source), style = MaterialTheme.typography.bodySmall)
            Text(line.product?.let(::productLine) ?: "No product found — tap Swap to search.")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasProduct = line.product != null
                TextButton({ onQty(q - 1) }, enabled = editable && hasProduct && !line.removed && q > 0) { Text("−") }
                Text("$q")
                TextButton({ onQty(q + 1) }, enabled = editable && hasProduct) { Text("+") }
                TextButton(onSwap, enabled = editable) { Text("Swap") }
                TextButton(onRemove, enabled = editable) { Text(if (line.removed) "Put back" else "Remove") }
            }
        }
    }
}

@Composable
private fun SwapDialog(swap: SwapState, onSearch: (String) -> Unit, onChoose: (Product) -> Unit, onClose: () -> Unit) {
    var term by remember(swap.lineId) { mutableStateOf(swap.term) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Swap ${swap.term}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(term, { term = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Search Loblaws") })
                    TextButton({ onSearch(term) }, enabled = !swap.searching && term.isNotBlank()) { Text("Search") }
                }
                if (swap.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                MessageText(swap.error)
                if (swap.results.isEmpty() && !swap.searching && swap.error == null) {
                    Text("No other suggestions — search for something.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(swap.results, key = { it.code }) { p ->
                        Text(productLine(p), Modifier.fillMaxWidth().clickable { onChoose(p) }.padding(vertical = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
fun DraftScreen(id: Int, onLoblaws: (String) -> Unit, onRebuild: (List<String>) -> Unit) {
    val vm = graphViewModel(key = "draft-$id") { g -> DraftViewModel(g.repo, id) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.sentCartId) { s.sentCartId?.let { vm.consumeSent(); onLoblaws(it) } }
    DraftContent(s, DraftActions(
        onQty = vm::setQuantity, onRemove = vm::setRemoved, onSwap = vm::openSwap,
        onSearch = vm::search, onChoose = vm::choose, onCloseSwap = vm::closeSwap,
        onSend = vm::send, onLoblaws = onLoblaws, onRebuild = onRebuild, onReload = vm::reload,
    ))
}
