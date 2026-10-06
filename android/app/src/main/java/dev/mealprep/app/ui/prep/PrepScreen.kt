package dev.mealprep.app.ui.prep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.PrepPlan
import dev.mealprep.app.data.api.PrepTask
import dev.mealprep.app.ui.common.AdviceText
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.announced
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate

/** The line under a task: which nights it serves, the estimate, and what the safety rules said. */
internal fun taskDetails(t: PrepTask): String = listOfNotNull(
    t.serves.joinToString(", ") { "${it.night} ${it.title}" }.ifBlank { null },
    t.estMinutes.takeIf { it > 0 }?.let { "about $it min" },
    when (t.shelfLife) { "freeze_then_thaw" -> "freeze"; else -> null },
).joinToString(" · ")

/** "2 of 4 done · about 27 min"; once everything is ticked, how long it really took. */
internal fun checklistText(p: PrepPlan): String {
    val c = p.checklist
    if (c.total > 0 && c.done >= c.total) return "All ${c.total} done" + (c.actualMinutes?.let { " · took $it min" } ?: "")
    return "${c.done} of ${c.total} done · about ${c.estMinutes} min"
}

class PrepActions(
    val onStart: () -> Unit = {},
    val onToggle: (PrepTask) -> Unit = {},
    val onCard: (Int) -> Unit = {},
    val onReload: () -> Unit = {},
)

@Composable
fun PrepContent(s: PrepState, week: LocalDate, today: LocalDate, a: PrepActions) {
    var confirmNew by remember { mutableStateOf(false) }
    // A new plan starts with nothing ticked: ask before throwing ticks away.
    val writeNew = { if (s.hasTicks) confirmNew = true else a.onStart() }
    val plan = s.shown
    val newest = s.plan
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Sunday prep", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(Weeks.weekChoiceLabel(week, today), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OfflineBanner(s.offlineSince); MessageText(s.error)
        }
        when {
            s.loading && newest == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            // Couldn't ask the server and nothing saved: unknown, not "no plan" (writing one could make a second).
            s.unknown -> item { Button(a.onReload) { Text("Try again") } }
            newest == null -> item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(NO_PLAN, style = MaterialTheme.typography.bodyLarge)
                        Button(a.onStart, enabled = !s.starting, modifier = Modifier.fillMaxWidth()) {
                            Text(if (s.starting) "Starting…" else "Write my prep plan")
                        }
                    }
                }
            }
            newest.status == "building" -> item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(WRITING)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(buildingText(newest), style = MaterialTheme.typography.bodySmall, modifier = Modifier.announced())
                    if (s.previous != null) Text("Below: the last plan, until the new one is ready.", style = MaterialTheme.typography.bodySmall)
                }
            }
            newest.status == "failed" -> item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("The prep plan couldn't be written: ${newest.error ?: "unknown error"}", Modifier.announced(),
                        color = MaterialTheme.colorScheme.error)
                    if (s.previous != null) Text("Below: the last plan that worked.", style = MaterialTheme.typography.bodySmall)
                    Button(a.onStart, enabled = !s.starting) { Text("Try again") }
                }
            }
        }
        if (plan != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val c = plan.checklist
                    Text(checklistText(plan), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator({ if (c.total == 0) 0f else c.done.toFloat() / c.total }, Modifier.fillMaxWidth())
                    // Only the newest ready plan can be out of date; a building/failed newer one already says so above.
                    // The button goes under the note: side by side, large text squeezed the note to a word a line.
                    if (plan.stale && plan.id == newest?.id) {
                        Text("The week changed since this plan was written.")
                        TextButton(writeNew, enabled = !s.starting) { Text("Write a new plan") }
                    }
                    plan.warnings.forEach { AdviceText("⚠ $it") }
                }
            }
            plan.sections.filter { it.tasks.isNotEmpty() }.forEach { sec ->
                item(key = "sec-${sec.key}") {
                    Text(sec.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                }
                items(sec.tasks, key = { "task-${it.id}" }) { t -> TaskRow(t, a.onToggle) }
            }
            item(key = "cards") {
                Column {
                    Text("Cook cards", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    plan.entries.filter { it.hasCard }.forEach { e ->
                        TextButton({ a.onCard(e.entryId) }, Modifier.fillMaxWidth()) { Text("${e.night}: ${e.title}", Modifier.fillMaxWidth()) }
                    }
                    plan.entries.filter { !it.hasCard }.forEach { e ->
                        Text("${e.title}: no cook card (${if (e.date == null) "not on a night" else "not written"})",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if ((newest == null && !s.loading && !s.unknown) || s.offlineSince != null) item { TextButton(a.onReload) { Text("Refresh") } }
    }
    if (confirmNew) AlertDialog(
        onDismissRequest = { confirmNew = false },
        title = { Text("Write a new plan?") },
        text = { Text("It takes about 4 minutes. The new plan starts with nothing ticked.") },
        confirmButton = { TextButton({ confirmNew = false; a.onStart() }) { Text("Write it") } },
        dismissButton = { TextButton({ confirmNew = false }) { Text("Keep this one") } },
    )
}

@Composable
private fun TaskRow(t: PrepTask, onToggle: (PrepTask) -> Unit) {
    val sunday = t.shelfLife != "day_of"
    Card(Modifier.fillMaxWidth()) {
        // The whole row is the checkbox (a big target with wet hands); day-of tasks are listed for the night only,
        // with a label instead of a disabled box (it looked broken).
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (sunday) Modifier.toggleable(t.done, role = Role.Checkbox) { onToggle(t) } else Modifier)
            .padding(8.dp), verticalAlignment = Alignment.Top) {
            if (sunday) Checkbox(t.done, onCheckedChange = null, modifier = Modifier.padding(end = 8.dp))
            Column {
                if (!sunday) Text(ON_THE_NIGHT, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(t.text, color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                taskDetails(t).ifBlank { null }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                t.thaw?.let { Text("Thaw: $it", style = MaterialTheme.typography.bodySmall) }
                t.contents.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

const val ON_THE_NIGHT = "On the night"
const val NO_PLAN = "No prep plan for this week yet. It's written from the recipes on this week's nights and takes about 4 minutes."
const val WRITING = "Writing your prep plan… usually about 4 minutes. You can leave — you'll get a notification."

@Composable
fun PrepScreen(week: LocalDate, onCard: (Int) -> Unit) {
    val vm = graphViewModel(key = "prep-$week") { g -> PrepViewModel(g.repo, g.jobs, week) }
    val s by vm.state.collectAsStateWithLifecycle()
    var today by remember { mutableStateOf(LocalDate.now()) }
    // Back on the screen (or the app resumed): the other phone may have ticked tasks. The first resume is the first load.
    var resumed by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        today = LocalDate.now()
        if (resumed) vm.reload() else resumed = true
        onPauseOrDispose { }
    }
    PrepContent(s, week, today, PrepActions(onStart = vm::start, onToggle = vm::toggle, onCard = onCard, onReload = vm::reload))
}
