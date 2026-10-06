package dev.mealprep.app.ui.card

import android.content.ActivityNotFoundException
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.data.api.CardStep
import dev.mealprep.app.data.api.CookCard
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.graphViewModel

/** The card's lists, in the order they matter on the night (empty ones are left out). */
fun cardSections(c: CookCard): List<Pair<String, List<String>>> =
    listOf("Last time" to c.ratingNotes, "Thaw" to c.thaw, "From your kit" to c.kit, "On the night" to c.dayOf)
        .filter { it.second.isNotEmpty() }

class CardUiActions(
    val onStep: (Int) -> Unit = {},
    val onTimer: (CookCard, CardStep, Int) -> Unit = { _, _, _ -> },
    val onPrint: (CookCard) -> Unit = {},
    val onReload: () -> Unit = {},
)

@Composable
fun CardContent(s: CardState, done: Set<Int>, a: CardUiActions) {
    val card = s.card
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            OfflineBanner(s.offlineSince); MessageText(s.message)
            if (s.loading && card == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (card == null) {
            if (!s.loading) item { TextButton(a.onReload) { Text("Refresh") } }
            return@LazyColumn
        }
        item {
            Text(card.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(CardActions.subtitle(card), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (card.stale) Text("This card was written before the recipe moved or changed size — write a new prep plan to refresh it.",
                color = MaterialTheme.colorScheme.error)
        }
        cardSections(card).forEach { (title, lines) ->
            item(key = title) {
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    lines.forEach { Text("• $it") }
                }
            }
        }
        item { Text("Steps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() }) }
        itemsIndexed(card.steps) { i, step -> StepRow(i, step, i in done, onStep = { a.onStep(i) }, onTimer = { m -> a.onTimer(card, step, m) }) }
        item {
            OutlinedButton({ a.onPrint(card) }, Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("Print this card") }
        }
    }
}

@Composable
private fun StepRow(i: Int, step: CardStep, checked: Boolean, onStep: () -> Unit, onTimer: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Ticks are only for keeping your place on this phone (not sent to the server).
            Row(Modifier.weight(1f).heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox) { onStep() }.padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
                Column {
                    Text("${i + 1}. ${step.text}",
                        color = if (checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    step.minutes?.let { Text("~$it min", style = MaterialTheme.typography.bodySmall) }
                }
            }
            step.timerMinutes?.takeIf { it > 0 }?.let { m ->
                TextButton({ onTimer(m) }, Modifier.semantics { contentDescription = "Start a $m-minute timer" }) { Text("Timer $m min") }
            }
        }
    }
}

@Composable
fun CardScreen(entryId: Int) {
    val vm = graphViewModel(key = "card-$entryId") { g -> CardViewModel(g.repo, entryId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }   // cooking: phone on the counter
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { if (resumed) vm.reload() else resumed = true; onPauseOrDispose { } }
    var done by rememberSaveable(entryId) { mutableStateOf(intArrayOf()) }
    var printing by remember { mutableStateOf<WebView?>(null) }
    CardContent(s, done.toSet(), CardUiActions(
        onStep = { i -> done = if (i in done) done.filter { it != i }.toIntArray() else done + i },
        onTimer = { card, step, m ->
            try {
                ctx.startActivity(CardActions.timerIntent(CardActions.timerLabel(card.title, step), m))
                Toast.makeText(ctx, "Timer set: $m min", Toast.LENGTH_SHORT).show()
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(ctx, "No clock app found for timers.", Toast.LENGTH_LONG).show()
            }
        },
        onPrint = { card -> if (printing == null) CardActions.print(ctx, card) { printing = it } },
        onReload = vm::reload,
    ))
}
