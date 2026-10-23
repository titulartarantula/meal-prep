package dev.mealprep.app.ui.fridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.R
import dev.mealprep.app.data.api.Pick
import dev.mealprep.app.data.api.Suggestion
import dev.mealprep.app.ui.common.AdviceText
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomActionBar
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.announced
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate

const val SUGGEST_TITLE = "Use up the fridge"
const val SUGGEST_OTHERS = "Suggest others"

data class SuggestActions(
    val onBack: () -> Unit = {},
    val onNights: (Int) -> Unit = {},
    val onPeople: (Int) -> Unit = {},
    val onToggle: (Int) -> Unit = {},
    val onOthers: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

@Composable
fun SuggestScreen(week: LocalDate, onDone: (String) -> Unit, onBack: () -> Unit) {
    val vm = graphViewModel(key = "suggest-$week") { g -> SuggestViewModel(g.repo, week) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { state.done?.let(onDone) }
    SuggestContent(state, vm.today(), SuggestActions(onBack, vm::setNights, vm::setPeople, vm::toggle, vm::suggestOthers, vm::addToWeek, vm::retry))
}

@Composable
fun SuggestContent(state: SuggestState, today: LocalDate, a: SuggestActions) {
    val r = state.result
    Column(Modifier.fillMaxSize()) {
        BackTopBar(SUGGEST_TITLE, a.onBack, subtitle = "Dinners for ${SuggestLogic.weekName(state.week, today)}")
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Stepper(SuggestLogic.nights(state.nights), "nights", state.nights, 1, SuggestViewModel.MAX_NIGHTS, a.onNights)
            Stepper(SuggestLogic.people(state.people), "people", state.people, 1, SuggestViewModel.MAX_PEOPLE, a.onPeople)
            if (state.loading || state.adding) LinearProgressIndicator(Modifier.fillMaxWidth())
            MessageText(state.error)
            if (state.error != null && r == null) TextButton(a.onRetry, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            if (r != null) LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SuggestLogic.thinText(r)?.let { t -> item(key = "thin") { Text(t, Modifier.padding(vertical = 4.dp).announced()) } }
                items(r.picks, key = { it.recipeId }) { p ->
                    PickCard(p, r, p.recipeId in state.ticked, enabled = !state.adding) { a.onToggle(p.recipeId) }
                }
            }
        }
        if (r != null && r.picks.isNotEmpty()) BottomActionBar {
            val n = state.ticked.count { id -> r.picks.any { it.recipeId == id } }
            Button(a.onAdd, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = n > 0 && !state.adding && !state.loading) {
                Text(SuggestLogic.addLabel(n, state.week, today))
            }
            TextButton(a.onOthers, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.adding && !state.loading) { Text(SUGGEST_OTHERS) }
        }
    }
}

/** "3 nights  − +": 48 dp buttons, the value read out when it changes. */
@Composable
private fun Stepper(text: String, what: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).announced(), style = MaterialTheme.typography.titleMedium)
        IconButton({ onChange(value - 1) }, Modifier.size(48.dp), enabled = value > min) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = "Fewer $what")
        }
        IconButton({ onChange(value + 1) }, Modifier.size(48.dp), enabled = value < max) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = "More $what")
        }
    }
}

/** One pick: a whole-card checkbox with the title, rating, what it uses (stale counts flagged) and what's still needed. */
@Composable
private fun PickCard(p: Pick, s: Suggestion, ticked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(ticked, enabled = enabled, role = Role.Checkbox) { onToggle() }
            .padding(12.dp), verticalAlignment = Alignment.Top) {
            Checkbox(ticked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                SuggestLogic.rating(p)?.let { (shown, said) -> Text(shown, Modifier.semantics { contentDescription = said }) }
                Text(SuggestLogic.uses(p))
                SuggestLogic.staleLines(p, s).forEach { AdviceText(it) }
                Text(SuggestLogic.stillNeed(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
