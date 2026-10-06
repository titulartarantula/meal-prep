package dev.mealprep.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.mealprep.app.core.Sources

/** "Which book?" with the household's book titles as one-tap suggestions, and an optional page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookFields(book: String, onBook: (String) -> Unit, page: String, onPage: (String) -> Unit, books: List<String>,
               modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(book, onBook, label = { Text("Book") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            supportingText = { Text("Leave it empty if you don't know; you can add it later.") })
        val hits = Sources.suggest(books, book)
        if (hits.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            hits.forEach { b -> SuggestionChip(onClick = { onBook(b) }, label = { Text(b) }) }
        }
        OutlinedTextField(page, onPage, label = { Text("Page (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
    }
}

/** The section title above [BookFields] on the scan confirm screen. */
@Composable
fun WhichBookTitle() = Text("Which book?", style = MaterialTheme.typography.titleMedium)
