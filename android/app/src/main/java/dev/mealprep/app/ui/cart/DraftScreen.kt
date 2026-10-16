package dev.mealprep.app.ui.cart

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
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import dev.mealprep.app.R
import dev.mealprep.app.ui.common.announced
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.AdviceText
import dev.mealprep.app.ui.common.ProductImage
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.mealprep.app.ui.common.graphViewModel
import java.util.Locale

internal fun price(p: Double?) = p?.let { String.format(Locale.US, "$%.2f", it) } ?: ""
private val SOURCE = mapOf("user" to "Your pick", "memory" to "Remembered", "ai" to "Suggested", "none" to "No match")

/** Callbacks of the draft screen (one object so the stateless content stays readable). */
class DraftActions(
    val onQty: (DraftLine, Int) -> Unit = { _, _ -> },
    val onRemove: (DraftLine, Boolean) -> Unit = { _, _ -> },
    /** Opens the product chooser for the line (Swap product, Search Loblaws). */
    val onSwap: (DraftLine) -> Unit = {},
    val onPin: (DraftLine, Boolean) -> Unit = { _, _ -> },
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
        val older = d != null && d.stale && d.status in setOf("ready", "sent")
        BackTopBar(if (older) "Older cart" else "Cart", a.onBack, subtitle = d?.let { weeksTitle(it.weeks, today) })
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
            if (older) StaleBanner(d.status == "ready") { a.onRebuild(d.weeks) }
            if (d == null && !s.loading) TextButton(a.onReload) { Text("Try again") }
            if (d != null && d.status != "building") {
                LazyColumn(Modifier.weight(1f)) {
                    items(d.lines, key = { it.id }) { line ->
                        LineRow(line, editable = s.editable, busy = s.busyLine == line.id,
                            onQty = { a.onQty(line, it) }, onRemove = { a.onRemove(line, !line.removed) }, onSwap = { a.onSwap(line) },
                            onPin = { a.onPin(line, it) })
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
                // On an older cart, Build a new cart (in the banner) is the main action.
                "sent" -> if (older) OutlinedButton(onClick = { d.pcxCartId?.let(a.onLoblaws) }, enabled = d.pcxCartId != null, modifier = full) {
                    Text("Open in Loblaws")
                } else Button(onClick = { d.pcxCartId?.let(a.onLoblaws) }, enabled = d.pcxCartId != null, modifier = full) {
                    Text("Open in Loblaws")
                }
                else -> Button(onClick = { a.onRebuild(d.weeks) }, modifier = full) { Text("Build it again") }
            }
        }
    }
}

/** On an older cart (the week's recipes changed since it was built): say so, and lead to a new cart. */
@Composable
private fun StaleBanner(unsent: Boolean, onBuild: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(if (unsent) "$STALE_CART $STALE_UNSENT" else STALE_CART, Modifier.announced(), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Button(onBuild, Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) { Text(BUILD_NEW_CART) }
        }
    }
}

const val STALE_CART = "The week has changed since this cart was built."
const val STALE_UNSENT = "This older cart can't be changed or sent."

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
    val each = line.product?.takeIf { !it.weighted }?.price?.takeIf { (line.quantity ?: 0) > 1 }?.let { "${price(it)} each" }
    return listOfNotNull(line.name, needText(line)?.replaceFirstChar(Char::lowercase),
        if (n == 1) line.recipes[0] else if (n > 1) "$n recipes" else null, each).joinToString(" · ")
}

/** The product (brand · name · size), or what's missing. */
internal fun productTitle(line: DraftLine): String =
    line.product?.let { p -> listOfNotNull(p.brand, p.name, p.packageSize).joinToString(" · ") } ?: "No product found for ${line.name}"

/**
 * One cart line: a small photo, the product and its price lead (that's what is being checked), one quiet line says
 * what the list asked for (and, when the usual product wasn't used, why), then big −/+ buttons and ⋮ (why this
 * amount, which recipes, Swap, Always buy this, Remove). A line without a product offers Search Loblaws directly.
 * A product sold by weight steps by its interval and shows grams ("300 g").
 */
@Composable
private fun LineRow(line: DraftLine, editable: Boolean, busy: Boolean, onQty: (Int) -> Unit, onRemove: () -> Unit,
                    onSwap: () -> Unit, onPin: (Boolean) -> Unit) {
    val q = line.quantity ?: 0
    val p = line.product
    val strike = if (line.removed) TextDecoration.LineThrough else null
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            if (p != null) ProductImage(p.image, null, Modifier.size(48.dp), dim = line.removed)
            Column(Modifier.weight(1f).padding(start = if (p != null) 12.dp else 0.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    if (line.pinned) Icon(painterResource(R.drawable.ic_pin), contentDescription = ALWAYS_BOUGHT,
                        Modifier.padding(end = 4.dp, top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(productTitle(line), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, textDecoration = strike,
                        color = if (p == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    p?.cost(maxOf(q, 1))?.let { Text(price(it), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge, textDecoration = strike) }
                }
                Text(lineDetail(line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                line.note?.let { AdviceText(it, Modifier.padding(top = 2.dp)) }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            when {
                line.removed -> TextButton(onRemove, enabled = editable) { Text("Put back") }
                p == null -> TextButton(onSwap, enabled = editable) { Text(SEARCH_LOBLAWS) }
                else -> {
                    // TalkBack: "One fewer onion", "Quantity 2" (said again when it changes), not "minus" / "2".
                    // Below the product's minimum, − removes the line (as 0 always did).
                    OutlinedIconButton({ onQty(ChooserText.fewer(q, p) ?: 0) }, Modifier.size(48.dp), enabled = editable && q > 0) {
                        Icon(painterResource(R.drawable.ic_remove), contentDescription = ChooserText.fewerLabel(p, line.name))
                    }
                    val shown = ChooserText.quantity(q, p)
                    Text(shown, Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "Quantity $shown" }.announced(),
                        style = MaterialTheme.typography.titleMedium)
                    val up = ChooserText.more(q, p)
                    OutlinedIconButton({ up?.let(onQty) }, Modifier.size(48.dp), enabled = editable && up != null) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = ChooserText.moreLabel(p, line.name))
                    }
                }
            }
            LineMenu(line, editable, onSwap, onRemove, onPin)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

const val SEARCH_LOBLAWS = "Search Loblaws"
const val STOP_ALWAYS_BUY = "Stop always buying this"

/** ⋮ on a cart line: why this amount and which recipes (read-only text), then Swap, Always buy this and Remove / Put back. */
@Composable
private fun LineMenu(line: DraftLine, editable: Boolean, onSwap: () -> Unit, onRemove: () -> Unit, onPin: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More for ${line.name}") }
        DropdownMenu(open, { open = false }) {
            val info = listOfNotNull(line.why, line.recipes.takeIf { it.isNotEmpty() }?.let { "For " + it.joinToString(", ") },
                (if (line.pinned) ALWAYS_BOUGHT else SOURCE[line.source])?.let { "Product: $it" })
            Column(Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                info.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider()
            DropdownMenuItem({ Text(if (line.product == null) SEARCH_LOBLAWS else "Swap product") }, { open = false; onSwap() }, enabled = editable)
            if (line.product != null && !line.removed) DropdownMenuItem({ Text(if (line.pinned) STOP_ALWAYS_BUY else ALWAYS_BUY) },
                { open = false; onPin(!line.pinned) }, enabled = editable)
            DropdownMenuItem({ Text(if (line.removed) "Put back" else "Remove from cart") }, { open = false; onRemove() }, enabled = editable)
        }
    }
}

@Composable
fun DraftScreen(id: Int, onLoblaws: (String) -> Unit, onRebuild: (List<String>) -> Unit, onChoose: (Int) -> Unit, onBack: () -> Unit) {
    val vm = graphViewModel(key = "draft-$id") { g -> DraftViewModel(g.repo, id) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.sentCartId) { s.sentCartId?.let { vm.consumeSent(); onLoblaws(it) } }
    // Back from the chooser: the line it changed (and anything the other phone changed).
    LifecycleResumeEffect(vm) { vm.refresh(); onPauseOrDispose { } }
    DraftContent(s, DraftActions(
        onQty = vm::setQuantity, onRemove = vm::setRemoved, onSwap = { if (s.editable) onChoose(it.id) }, onPin = vm::setPinned,
        onSend = vm::send, onLoblaws = onLoblaws, onRebuild = onRebuild, onReload = vm::reload, onBack = onBack,
    ))
}
