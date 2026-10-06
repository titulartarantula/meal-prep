package dev.mealprep.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mealprep.app.core.BookSuggestion
import dev.mealprep.app.core.BookSuggestions

/** "Which book?" with suggestions as one-tap rows (the household's books first, then the server's book search),
 *  and an optional page. Anything typed is fine; a picked suggestion also brings its author and ISBN. */
@Composable
fun BookFields(book: String, onBook: (String) -> Unit, page: String, onPage: (String) -> Unit,
               suggestions: BookSuggestions, onPick: (BookSuggestion) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(book, onBook, label = { Text("Book") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            supportingText = { Text("Leave it empty if you don't know; you can add it later.") })
        SuggestionGroup("Your books", suggestions.yours, onPick)
        SuggestionGroup("Book search", suggestions.found, onPick)
        OutlinedTextField(page, onPage, label = { Text("Page (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
    }
}

@Composable
private fun SuggestionGroup(label: String, rows: List<BookSuggestion>, onPick: (BookSuggestion) -> Unit) {
    if (rows.isEmpty()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        rows.forEachIndexed { i, s ->
            if (i > 0) HorizontalDivider()
            SuggestionRow(s, onPick)
        }
    }
}

/** At least 48 dp tall; wraps (no fixed height) so it holds at 200 % text. TalkBack: "Title, by Author, 1961". */
@Composable
private fun SuggestionRow(s: BookSuggestion, onPick: (BookSuggestion) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(onClickLabel = "Use this book", role = Role.Button) { onPick(s) }
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
        Text(s.display, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        s.secondary?.let { t ->
            Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = s.spoken ?: t })
        }
    }
}

/** The section title above the Book / Other choice on the scan confirm screen. */
@Composable
fun WhereFromTitle() = Text("Where is it from?", style = MaterialTheme.typography.titleMedium)
