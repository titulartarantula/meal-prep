package dev.mealprep.app.ui.cart

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.data.api.Draft
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.TabHeader
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListContent(
    state: ListState, onWeek: (LocalDate) -> Unit, onToggle: (String) -> Unit, onBuild: () -> Unit, onRetry: () -> Unit = {},
    onStaple: (Int) -> Unit = {}, onEditStaples: () -> Unit = {}, onOpenDraft: (Int) -> Unit = {},
    today: LocalDate = LocalDate.now(),
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text("Shopping for", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.options.forEach { o ->
                FilterChip(o.week in state.weeks, { onWeek(o.week) }, { Text(o.label + (o.detail?.let { " · $it" } ?: "")) })
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        MessageText(state.error)
        if (!state.loading && state.items.isEmpty() && state.error != null) TextButton(onRetry) { Text("Try again") }
        LazyColumn(Modifier.weight(1f)) {
            state.existing?.let { d -> item { ExistingCart(d) { onOpenDraft(d.id) } } }
            if (state.staples.isNotEmpty() || state.stapleError != null) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Staples", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                    TextButton(onEditStaples) { Text("Edit staples") }
                }
                Text("Ticked staples go in the cart. Untick what you don't need this week.", style = MaterialTheme.typography.bodySmall)
                MessageText(state.stapleError)
            }
            items(state.staples, key = { "staple-${it.id}" }) { s ->
                CheckRow(s.id in state.ticked, enabled = !state.building && state.draftId == null, onToggle = { onStaple(s.id) },
                    title = "${s.name} · ${stapleAmount(s)}", lines = listOf(lastBoughtText(s.lastBought, today)))
            }
            if (state.onlyStaples) item {
                Text("No recipes planned for that week yet — only staples.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp))
            }
            if (state.toBuy.isNotEmpty()) item {
                Text("To buy", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            }
            items(state.toBuy, key = { it.key }) { ItemRow(it, onToggle) }
            if (state.probablyHave.isNotEmpty()) item {
                Text("Probably have — tick what you need", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() })
            }
            items(state.probablyHave, key = { it.key }) { ItemRow(it, onToggle) }
        }
        Button(onClick = onBuild, enabled = state.canBuild, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(if (state.building) "Starting…" else "Build cart (${state.neededCount} items)")
        }
    }
}

/** A cart already made for the chosen week: the list leads back to it whatever its state. */
@Composable
private fun ExistingCart(d: Draft, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(existingCartText(d), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onOpen) { Text(if (d.status == "sent") "Open cart" else "Review cart") }
        }
    }
}

internal fun existingCartText(d: Draft): String = when (d.status) {
    "sent" -> "A cart for this week was already sent to Loblaws."
    "building" -> "A cart for this week is being built."
    else -> "A cart for this week is ready to review."
}

/** A whole-row checkbox (48 dp tall at least; TalkBack reads it as one checkbox with its text). */
@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit, title: String, lines: List<String>) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox) { onToggle() },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(title)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ItemRow(item: ListItem, onToggle: (String) -> Unit) =
    CheckRow(item.needed, onToggle = { onToggle(item.key) }, title = itemText(item),
        lines = listOfNotNull(item.prep, item.recipes.joinToString(", ").takeIf { item.recipes.isNotEmpty() }))

/** "3 onion", "⅚ cup whole milk" — the prep note ("diced") is shown on its own line under it. */
internal fun itemText(item: ListItem): String = listOfNotNull(amountText(item.qty, item.unit), item.name).joinToString(" ")

/** "⅚ cup", "1½ tbsp", "3"; null when there's no amount. */
internal fun amountText(q: Double?, unit: String?): String? =
    q?.let { listOfNotNull(qty(it), unit).joinToString(" ") }

private val FRACTIONS = listOf(1.0 / 8 to "⅛", 1.0 / 6 to "⅙", 1.0 / 4 to "¼", 1.0 / 3 to "⅓", 3.0 / 8 to "⅜", 1.0 / 2 to "½",
    5.0 / 8 to "⅝", 2.0 / 3 to "⅔", 3.0 / 4 to "¾", 5.0 / 6 to "⅚", 7.0 / 8 to "⅞")

/** Kitchen amounts: 0.83 → "⅚", 1.5 → "1½", 2.0 → "2"; anything else to two decimals ("0.9"). */
internal fun qty(q: Double): String {
    val whole = kotlin.math.floor(q)
    val frac = q - whole
    if (frac < 0.02) return whole.toLong().toString()
    if (frac > 0.98) return (whole.toLong() + 1).toString()
    FRACTIONS.firstOrNull { kotlin.math.abs(it.first - frac) < 0.02 }?.let { (_, f) ->
        return if (whole == 0.0) f else "${whole.toLong()}$f"
    }
    return String.format(Locale.US, "%.2f", q).trimEnd('0').trimEnd('.')
}

@Composable
fun ListScreen(
    weeks: List<LocalDate>, onDraft: (Int) -> Unit, onOpenDraft: (Int) -> Unit, onEditStaples: () -> Unit,
    menu: List<Pair<String, Any>> = emptyList(), onOpen: (Any) -> Unit = {},
) {
    val vm = graphViewModel(key = "list-$weeks") { g -> ListViewModel(g.repo, g.jobs, weeks) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.draftId) { state.draftId?.let(onDraft) }
    // Back from the Staples screen: pick up added/removed/edited staples.
    LifecycleResumeEffect(Unit) { vm.refreshStaples(); onPauseOrDispose { } }
    Column(Modifier.fillMaxSize()) {
        TabHeader("Shopping list", menu, onOpen)
        Box(Modifier.weight(1f)) { ListContent(state, vm::toggleWeek, vm::toggle, vm::buildCart, vm::retry, vm::toggleStaple, onEditStaples, onOpenDraft) }
    }
}
