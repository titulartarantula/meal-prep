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
import androidx.compose.material3.FilterChip
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
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.theme.GardenAccent
import java.time.LocalDate

/** "How was Tuesday's Chili?" (or without the night when it has none). */
fun ratingQuestion(title: String, night: String): String =
    if (night.isNotBlank()) "How was $night's $title?" else "How was $title?"

internal val COMPANY = listOf("yes" to "Yes", "maybe" to "Maybe", "no" to "No")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RatingContent(
    s: RatingState, onFamily: (Int) -> Unit, onCompany: (String) -> Unit, onNote: (String) -> Unit,
    onSave: () -> Unit, onClear: () -> Unit, onClose: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (s.title.isEmpty()) "Rate this dinner" else ratingQuestion(s.title, s.night),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClose) { Text("Close") }
        }
        OfflineBanner(s.offlineSince)
        if (s.loading || s.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.title.isEmpty()) { MessageText(s.error); return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            s.summary?.let(RatingText::summary)?.let {
                Text("So far: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                Text("Family: was it a hit?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text("1 = not again, 5 = a big hit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..5).forEach { n ->
                        FilterChip(s.family == n, { onFamily(n) }, { Text("$n") }, colors = GardenAccent.chipColors(),
                            modifier = Modifier.semantics { contentDescription = "$n out of 5" })
                    }
                }
            }
            Column {
                Text("Would you make it for company?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Text("Optional. Tap your choice again to clear it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    COMPANY.forEach { (v, l) -> FilterChip(s.company == v, { onCompany(v) }, { Text(l) }, colors = GardenAccent.chipColors()) }
                }
            }
            OutlinedTextField(s.note, onNote, label = { Text("Note (optional)") }, placeholder = { Text("e.g. less salt next time") },
                modifier = Modifier.fillMaxWidth())
            Text("Notes show on this recipe's cook card the next time it's planned.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MessageText(s.error)
            Button(onSave, enabled = !s.saving && !s.loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (s.existing) "Update rating" else "Save rating")
            }
            if (s.existing) TextButton(onClear, enabled = !s.saving) { Text("Remove rating") }
            s.summary?.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                Column {
                    Text("Earlier notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    notes.forEach { n -> Text("“${n.note}”" + (n.date?.let { d -> " — ${shortDate(d)}" } ?: "")) }
                }
            }
        }
    }
}

private fun shortDate(iso: String) = runCatching { Weeks.shortDate(LocalDate.parse(iso)) }.getOrDefault(iso)

@Composable
fun RatingScreen(entryId: Int, week: LocalDate, afterChange: () -> Unit, onDone: () -> Unit) {
    val vm = graphViewModel(key = "rating-$entryId") { g -> RatingViewModel(g.repo, entryId, week, afterChange) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.saved) { if (s.saved) onDone() }
    RatingContent(s, vm::setFamily, vm::setCompany, vm::setNote, vm::save, vm::clear, onClose = onDone)
}
