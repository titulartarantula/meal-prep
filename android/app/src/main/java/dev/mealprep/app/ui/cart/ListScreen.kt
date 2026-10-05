package dev.mealprep.app.ui.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.data.api.ListItem
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListContent(state: ListState, onWeek: (LocalDate) -> Unit, onToggle: (String) -> Unit, onBuild: () -> Unit, onRetry: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
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
            if (state.toBuy.isNotEmpty()) item { Text("To buy", style = MaterialTheme.typography.titleSmall) }
            items(state.toBuy, key = { it.key }) { ItemRow(it, onToggle) }
            if (state.probablyHave.isNotEmpty()) item {
                Text("Probably have — tick what you need", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            }
            items(state.probablyHave, key = { it.key }) { ItemRow(it, onToggle) }
        }
        Button(onClick = onBuild, enabled = state.canBuild, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.building) "Starting…" else "Build cart (${state.neededCount} items)")
        }
    }
}

@Composable
private fun ItemRow(item: ListItem, onToggle: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(item.needed, { onToggle(item.key) })
        Column {
            Text(itemText(item))
            if (item.recipes.isNotEmpty()) Text(item.recipes.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** "3 onion, diced", "2 tbsp olive oil". */
internal fun itemText(item: ListItem): String =
    listOfNotNull(item.qty?.let(::qty), item.unit, item.name).joinToString(" ") + (item.prep?.let { ", $it" } ?: "")

internal fun qty(q: Double): String =
    if (q % 1.0 == 0.0) q.toLong().toString() else String.format(Locale.US, "%.2f", q).trimEnd('0').trimEnd('.')

@Composable
fun ListScreen(weeks: List<LocalDate>, onDraft: (Int) -> Unit) {
    val vm = graphViewModel(key = "list-$weeks") { g -> ListViewModel(g.repo, g.jobs, weeks) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.draftId) { state.draftId?.let(onDraft) }
    ListContent(state, vm::toggleWeek, vm::toggle, vm::buildCart, vm::retry)
}
