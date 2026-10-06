package dev.mealprep.app.ui.cart

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedIconButton
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomActionBar
import java.time.LocalDate
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import dev.mealprep.app.R
import dev.mealprep.app.ui.common.announced
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    val onBack: () -> Unit = {},
)

@Composable
fun DraftContent(s: DraftState, a: DraftActions, today: LocalDate = LocalDate.now()) {
    val d = s.draft
    Column(Modifier.fillMaxSize()) {
        BackTopBar("Cart", a.onBack, subtitle = d?.let { weeksTitle(it.weeks, today) })
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (d == null || d.status == "building") {
                if (d != null || s.loading) {
                    Text(d?.let { "Finding products… ${it.progress.done} of ${it.progress.total}" } ?: "Loading…", Modifier.announced())
                    // The count is known while building: a bar that fills, not one that only moves.
                    val p = d?.progress
                    if (p != null && p.total > 0) LinearProgressIndicator({ p.done.toFloat() / p.total }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("You can leave this screen — you'll get a notification when it's ready.", style = MaterialTheme.typography.bodySmall)
                }
            }
            MessageText(s.error)
            if (d == null && !s.loading) TextButton(a.onReload) { Text("Try again") }
            if (d != null && d.status != "building") {
                LazyColumn(Modifier.weight(1f)) {
                    items(d.lines, key = { it.id }) { line ->
                        LineRow(line, editable = s.editable, busy = s.busyLine == line.id,
                            onQty = { a.onQty(line, it) }, onRemove = { a.onRemove(line, !line.removed) }, onSwap = { a.onSwap(line) })
                    }
                }
            }
        }
        if (d != null && d.status != "building") BottomActionBar(above = {
            if (d.status == "ready" || d.status == "sent") {
                Text("About ${price(s.total)} · ${s.itemCount} ${if (s.itemCount == 1) "item" else "items"}",
                    Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium)
            } else Text("This cart couldn't be built.", Modifier.padding(bottom = 8.dp))
        }) {
            val full = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            when (d.status) {
                "ready" -> Button(onClick = a.onSend, enabled = s.canSend, modifier = full) {
                    Text(if (s.sending) "Sending…" else "Send to Loblaws")
                }
                "sent" -> Button(onClick = { d.pcxCartId?.let(a.onLoblaws) }, enabled = d.pcxCartId != null, modifier = full) {
                    Text("Open in Loblaws")
                }
                else -> Button(onClick = { a.onRebuild(d.weeks) }, modifier = full) { Text("Build it again") }
            }
        }
    }
    s.swap?.let { swap -> SwapDialog(swap, a.onSearch, a.onChoose, a.onCloseSwap) }
}

/** The cart's weeks under its title: "This week (Oct 11)", "Oct 11 + Oct 18". */
internal fun weeksTitle(weeks: List<String>, today: LocalDate): String? {
    val ws = weeks.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
    return when (ws.size) {
        0 -> null
        1 -> Weeks.weekChoiceLabel(ws[0], today)
        else -> ws.joinToString(" + ") { Weeks.shortDate(it) }
    }
}

/** What the recipes need, under the item name: "Need ⅚ cup · warmed"; null when there's nothing to say. */
internal fun needText(line: DraftLine): String? =
    listOfNotNull(amountText(line.qty, line.unit)?.let { "Need $it" }, line.prep).joinToString(" · ").ifEmpty { null }

/** The quiet line under the product: "onion · need 4 · diced · 3 recipes" (one recipe by name). */
internal fun lineDetail(line: DraftLine): String {
    val n = line.recipes.size
    val each = line.product?.price?.takeIf { (line.quantity ?: 0) > 1 }?.let { "${price(it)} each" }
    return listOfNotNull(line.name, needText(line)?.replaceFirstChar(Char::lowercase),
        if (n == 1) line.recipes[0] else if (n > 1) "$n recipes" else null, each).joinToString(" · ")
}

/** The product (brand · name · size), or what's missing. */
internal fun productTitle(line: DraftLine): String =
    line.product?.let { p -> listOfNotNull(p.brand, p.name, p.packageSize).joinToString(" · ") } ?: "No product found for ${line.name}"

/**
 * One cart line: the product and its price lead (that's what is being checked), one quiet line says what the list
 * asked for, then big −/+ buttons and ⋮ (why this amount, which recipes, Swap, Remove). A line without a product
 * offers Search Loblaws directly.
 */
@Composable
private fun LineRow(line: DraftLine, editable: Boolean, busy: Boolean, onQty: (Int) -> Unit, onRemove: () -> Unit, onSwap: () -> Unit) {
    val q = line.quantity ?: 0
    val p = line.product
    val strike = if (line.removed) TextDecoration.LineThrough else null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(productTitle(line), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, textDecoration = strike,
                color = if (p == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            p?.price?.let { Text(price(it * maxOf(q, 1)), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, textDecoration = strike) }
        }
        Text(lineDetail(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when {
                line.removed -> TextButton(onRemove, enabled = editable) { Text("Put back") }
                p == null -> TextButton(onSwap, enabled = editable) { Text(SEARCH_LOBLAWS) }
                else -> {
                    // TalkBack: "One fewer onion", "Quantity 2" (said again when it changes), not "minus" / "2".
                    OutlinedIconButton({ onQty(q - 1) }, Modifier.size(48.dp), enabled = editable && q > 0) {
                        Icon(painterResource(R.drawable.ic_remove), contentDescription = "One fewer ${line.name}")
                    }
                    Text("$q", Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "Quantity $q" }.announced(),
                        style = MaterialTheme.typography.titleMedium)
                    OutlinedIconButton({ onQty(q + 1) }, Modifier.size(48.dp), enabled = editable) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "One more ${line.name}")
                    }
                }
            }
            LineMenu(line, editable, onSwap, onRemove)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

const val SEARCH_LOBLAWS = "Search Loblaws"

/** ⋮ on a cart line: why this amount and which recipes (read-only text), then Swap and Remove / Put back. */
@Composable
private fun LineMenu(line: DraftLine, editable: Boolean, onSwap: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More for ${line.name}") }
        DropdownMenu(open, { open = false }) {
            val info = listOfNotNull(line.why, line.recipes.takeIf { it.isNotEmpty() }?.let { "For " + it.joinToString(", ") },
                SOURCE[line.source]?.let { "Product: $it" })
            Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                info.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider()
            DropdownMenuItem({ Text(if (line.product == null) SEARCH_LOBLAWS else "Swap product") }, { open = false; onSwap() }, enabled = editable)
            DropdownMenuItem({ Text(if (line.removed) "Put back" else "Remove from cart") }, { open = false; onRemove() }, enabled = editable)
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
                // Search is the keyboard's action and an icon in the field: a button beside it left the field too
                // narrow for its label at large text.
                val canSearch = !swap.searching && term.isNotBlank()
                OutlinedTextField(term, { term = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Search Loblaws") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch(term) }),
                    trailingIcon = {
                        IconButton({ onSearch(term) }, enabled = canSearch) {
                            Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                        }
                    })
                if (swap.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                MessageText(swap.error)
                if (swap.results.isEmpty() && !swap.searching && swap.error == null) {
                    Text("No other suggestions — search for something.", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(swap.results, key = { it.code }) { p ->
                        Text(productLine(p), Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = "Choose") { onChoose(p) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClose) { Text("Cancel") } },
    )
}

@Composable
fun DraftScreen(id: Int, onLoblaws: (String) -> Unit, onRebuild: (List<String>) -> Unit, onBack: () -> Unit) {
    val vm = graphViewModel(key = "draft-$id") { g -> DraftViewModel(g.repo, id) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.sentCartId) { s.sentCartId?.let { vm.consumeSent(); onLoblaws(it) } }
    DraftContent(s, DraftActions(
        onQty = vm::setQuantity, onRemove = vm::setRemoved, onSwap = vm::openSwap,
        onSearch = vm::search, onChoose = vm::choose, onCloseSwap = vm::closeSwap,
        onSend = vm::send, onLoblaws = onLoblaws, onRebuild = onRebuild, onReload = vm::reload, onBack = onBack,
    ))
}
