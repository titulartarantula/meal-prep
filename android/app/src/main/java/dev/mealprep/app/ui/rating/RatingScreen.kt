package dev.mealprep.app.ui.rating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.core.RatingText
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.common.GardenChip
import java.time.LocalDate

/** "How was Tuesday's Chili?" (or without the night when it has none). */
fun ratingQuestion(title: String, night: String): String =
    if (night.isNotBlank()) "How was $night's $title?" else "How was $title?"

internal val COMPANY = listOf("yes" to "Yes", "no" to "No")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingContent(
    s: RatingState, onFamily: (Int) -> Unit, onCompany: (String) -> Unit, onNote: (String) -> Unit,
    onSave: () -> Unit, onClear: () -> Unit, onBack: () -> Unit = {}, onWho: (Int?) -> Unit = {},
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove this rating?") },
        text = { Text(if (s.note.isNotBlank()) "Its score and note are removed for this night." else "Its score is removed for this night.") },
        confirmButton = { TextButton({ confirmRemove = false; onClear() }) { Text("Remove") } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Keep it") } },
    )
    Column(Modifier.fillMaxSize()) {
        BackTopBar(if (s.title.isEmpty()) "Rate this dinner" else ratingQuestion(s.title, s.night), onBack)
        RatingBody(s, onFamily, onCompany, onNote, onSave, onRemove = { confirmRemove = true }, onWho)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RatingBody(
    s: RatingState, onFamily: (Int) -> Unit, onCompany: (String) -> Unit, onNote: (String) -> Unit, onSave: () -> Unit, onRemove: () -> Unit,
    onWho: (Int?) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OfflineBanner(s.offlineSince)
        if (s.loading || s.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.title.isEmpty()) { MessageText(s.error); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.summary?.let(RatingText::summary)?.let {
                Text("So far: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.raters.size > 1) Column {
                Text("Rating for", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.raters.forEach { r -> GardenChip(s.who == r.id, { onWho(r.id) }, r.label) }
                }
            }
            if (s.others.isNotEmpty()) Column {
                Text("This dinner so far", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                s.others.forEach { r -> RatingText.personLine(r, s.me).let { Text(it, Modifier.semantics { contentDescription = RatingText.spoken(it) }) } }
            }
            Column {
                Text("${s.whose}: was it a hit?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text("1 = not again, 5 = a big hit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n -> GardenChip(s.family == n, { onFamily(n) }, "$n", description = "$n out of 5") }
                }
            }
            OutlinedTextField(s.note, onNote, label = { Text("Note (optional)") }, placeholder = { Text("e.g. less salt next time") },
                modifier = Modifier.fillMaxWidth())
            Text("Notes show on the recipe and here the next time it's rated.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Below the note, apart from the score: the recipe's one household Yes / No, never a second scale.
            if (s.canSetCompany) Column {
                Text("Good for company?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text("One Yes / No for the household, not part of your rating. Tap it again to clear it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COMPANY.forEach { (v, l) -> GardenChip(s.company == v, { onCompany(v) }, l) }
                }
            } else s.summary?.let { Text(RatingText.company(it.copy(company = s.company)), style = MaterialTheme.typography.bodySmall) }
            MessageText(s.error)
            Button(onSave, enabled = !s.saving && !s.loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.existing) "Update rating" else "Save rating")
            }
            // Asks first: the screen closes once it's gone, and the note goes with it.
            if (s.existing) TextButton(onRemove, enabled = !s.saving) { Text("Remove rating") }
            s.summary?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                Column {
                    Text("Earlier notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    notes.forEach { n -> Text(RatingText.note(n, s.me, ::shortDate)) }
                }
            }
        }
    }
}

private fun shortDate(iso: String) = runCatching { Weeks.shortDate(LocalDate.parse(iso)) }.getOrDefault(iso)

@Composable
fun RatingScreen(entryId: Int, week: LocalDate, afterChange: () -> Unit, onDone: () -> Unit) {
    val vm = graphViewModel(key = "rating-$entryId") { g ->
        val prefs = g.settings.value
        RatingViewModel(g.repo, entryId, week, prefs.account, prefs.signedIn, afterChange)
    }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    RatingContent(s, vm::setFamily, vm::setCompany, vm::setNote, vm::save, vm::clear, onBack = onDone, onWho = vm::setWho)
}
