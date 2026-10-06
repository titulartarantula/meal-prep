package dev.mealprep.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.mealprep.app.core.Sources
import dev.mealprep.app.ui.theme.GardenAccent

/** Book or Other (a family recipe, a card from a friend …): which fields follow. */
@Composable
fun SourceKindChips(kind: String, onKind: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(kind == Sources.BOOK, { onKind(Sources.BOOK) }, { Text("Book") }, colors = GardenAccent.chipColors())
        FilterChip(kind == Sources.OTHER, { onKind(Sources.OTHER) }, { Text("Other") }, colors = GardenAccent.chipColors())
    }
}

/** An other source: a name (required, e.g. "Mum's recipes") with the household's saved names as one-tap chips (no
 *  lookup anywhere), and an optional note shown after the name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OtherFields(name: String, onName: (String) -> Unit, note: String, onNote: (String) -> Unit, names: List<String>,
                modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(name, onName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            supportingText = { Text("Needed, e.g. Mum's recipes") })
        val shown = Sources.suggestNames(names, name)
        if (shown.isNotEmpty()) {
            Text("Used before", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { n -> SuggestionChip({ onName(n) }, { Text(n) }) }
            }
        }
        OutlinedTextField(note, onNote, label = { Text("Note (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            supportingText = { Text("Shown after the name, e.g. the blue binder") })
    }
}
