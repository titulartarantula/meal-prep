package dev.mealprep.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.core.BookChoice
import dev.mealprep.app.core.BookSuggestion
import dev.mealprep.app.core.Books
import dev.mealprep.app.core.RatingText
import dev.mealprep.app.core.Sources
import dev.mealprep.app.ui.common.BookFields
import dev.mealprep.app.ui.common.OtherFields
import dev.mealprep.app.ui.common.SourceKindChips
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.ui.camera.refPrompt
import dev.mealprep.app.ui.camera.refPromptText
import dev.mealprep.app.ui.cart.withSelected
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.WeekOption
import dev.mealprep.app.ui.common.WeekPicker
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.home.refRoute
import dev.mealprep.app.ui.theme.GardenAccent
import dev.mealprep.app.work.SyncWorker
import java.time.LocalDate

@Composable
fun RecipeScreen(id: Int, onOpen: (Any) -> Unit, onWeek: (LocalDate) -> Unit, onClose: () -> Unit, addToWeek: Boolean = false) {
    val vm = graphViewModel(key = "recipe-$id") { g -> RecipeViewModel(g.repo, id, afterChange = { SyncWorker.now(g.workManager) }) }
    val state by vm.state.collectAsStateWithLifecycle()
    RecipeContent(state, onAdd = vm::addToWeek, onWeek = onWeek, onOpen = onOpen, onDismissAdded = vm::dismissAdded,
        onRetry = vm::load, onClose = onClose, startPicking = addToWeek, onEditSource = vm::editSource, onStartEdit = vm::loadBooks,
        onBookTyped = vm::bookTyped, onEditOther = vm::editOther)
}

@Composable
private fun Section(title: String) =
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp).semantics { heading() })

@Composable
fun RecipeContent(
    state: RecipeState,
    onAdd: (LocalDate, Int?) -> Unit,
    onWeek: (LocalDate) -> Unit,
    onOpen: (Any) -> Unit,
    onDismissAdded: () -> Unit,
    onRetry: () -> Unit = {},
    onClose: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
    /** Opened from "Add to a week…" on an import card or notification: show the week picker at once. */
    startPicking: Boolean = false,
    /** Book, page, then done(saved). */
    onEditSource: (BookChoice, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
    onStartEdit: () -> Unit = {},
    /** Edit source's book field changed (the book search). */
    onBookTyped: (String) -> Unit = {},
    /** Another source's name and note, then done(saved). */
    onEditOther: (String, String, (Boolean) -> Unit) -> Unit = { _, _, _ -> },
) {
    var picking by rememberSaveable { mutableStateOf(startPicking) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val r = state.recipe
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(r?.title ?: "Recipe", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClose) { Text("Close") }
        }
        OfflineBanner(state.offlineSince)
        MessageText(state.error)
        if (state.loading || state.adding) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (r == null) {
            if (!state.loading) TextButton(onRetry) { Text("Try again") }
        } else RecipeBody(r, state, today, onWeek, onOpen, onDismissAdded, onPick = { picking = true }, onEditSource = { editing = true; onStartEdit() })
    }
    if (editing && r != null) EditSourceDialog(r, state.books, state.found, state.savingSource, state.sourceError,
        onSave = { b, p -> onEditSource(b, p) { ok -> if (ok) editing = false } }, onDismiss = { editing = false },
        onBookTyped = onBookTyped, others = state.others,
        onSaveOther = { n, note -> onEditOther(n, note) { ok -> if (ok) editing = false } })
    if (picking) AddToWeekDialog(state.options, r?.plannedWeeks.orEmpty(), today,
        onAdd = { w, d -> picking = false; onAdd(w, d) }, onDismiss = { picking = false })
}

@Composable
private fun ColumnScope.RecipeBody(
    r: Recipe, state: RecipeState, today: LocalDate, onWeek: (LocalDate) -> Unit, onOpen: (Any) -> Unit,
    onDismissAdded: () -> Unit, onPick: () -> Unit, onEditSource: () -> Unit,
) {
    state.added?.let { a ->
        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(a.text, color = if (a.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Row {
                    a.week?.let { w -> TextButton({ onWeek(w) }) { Text("Open that week") } }
                    TextButton(onDismissAdded) { Text("OK") }
                }
            }
        }
    }
    Button(onPick, enabled = !state.adding, modifier = Modifier.fillMaxWidth()) { Text("Add to a week") }
    LazyColumn(Modifier.weight(1f)) {
        item {
            val small = MaterialTheme.typography.bodySmall
            RatingText.summary(r.ratings)?.let { Text(it) } ?: Text("Not rated yet", style = small)
            plannedText(r.plannedWeeks, today)?.let { Text(it, style = small) }
            SourceLine(r, onEditSource)
            r.sourceUrl?.let { url ->
                val uri = LocalUriHandler.current
                TextButton({ runCatching { uri.openUri(url) } }) { Text("Open on NYT Cooking") }
            }
            refPrompt(r)?.let { p ->
                Text(refPromptText(p), style = small)
                TextButton({ onOpen(refRoute(p)) }) { Text("Add photo of p.${p.page}") }
            }
        }
        if (r.ratings.notes.isNotEmpty()) {
            item { Section("Notes") }
            itemsIndexed(r.ratings.notes) { _, n ->
                Text("“${n.note}”" + (n.date?.let { d -> " — ${runCatching { Weeks.shortDate(LocalDate.parse(d)) }.getOrDefault(d)}" } ?: ""))
            }
        }
        item { Section("Ingredients") }
        ingredientLines(r).forEach { line ->
            item { if (line.heading) Text(line.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) else Text("• ${line.text}") }
        }
        if (r.steps.isNotEmpty()) {
            item { Section("Steps") }
            itemsIndexed(r.steps) { i, s -> Text("${i + 1}. $s", Modifier.padding(vertical = 2.dp)) }
        }
        if (r.history.isNotEmpty()) {
            item { Section("On the plan") }
            itemsIndexed(r.history) { _, h ->
                val week = runCatching { LocalDate.parse(h.week) }.getOrNull()
                Text(listOfNotNull(week?.let { "Week of ${Weeks.shortDate(it)}" } ?: h.week,
                    h.day?.let(Weeks::dayLabel) ?: "no night", h.rating?.let { "rated ${it.family}/5" }).joinToString(" · "))
            }
        }
    }
}

/** Ingredient lines as written; an attached sub-recipe's lines get its name as a heading. Lines whose page was
 *  attached ("Batter for 24 crêpes, page 191") are left out: the sub-recipe's own lines replace them. */
data class IngredientLine(val text: String, val heading: Boolean = false)

fun ingredientLines(r: Recipe): List<IngredientLine> {
    val out = mutableListOf<IngredientLine>()
    var group: String? = null
    r.ingredients.filter { !it.expanded }.forEach { ing ->
        if (ing.subRecipe != group) {
            group = ing.subRecipe
            group?.let { out += IngredientLine("$it:", heading = true) }
        }
        out += IngredientLine(ing.raw.ifBlank { ing.name })
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddToWeekDialog(
    options: List<WeekOption>, planned: List<String>, today: LocalDate, onAdd: (LocalDate, Int?) -> Unit, onDismiss: () -> Unit,
) {
    var week by remember { mutableStateOf(Weeks.upcomingSunday(today)) }
    var night by remember { mutableStateOf<Int?>(null) }
    val already = week.toString() in planned
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to a week") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WeekPicker(withSelected(options.ifEmpty { listOf(WeekOption(week, Weeks.weekChoiceLabel(week, today), null)) }, setOf(week), today),
                    week) { week = it }
                Text("Night (optional)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(night == null, { night = null }, { Text("No night yet") }, colors = GardenAccent.chipColors())
                    (0..6).forEach { d -> FilterChip(night == d, { night = d }, { Text(Weeks.dayLabel(d)) }, colors = GardenAccent.chipColors()) }
                }
                if (already) Text("It's already in that week. Pick another week, or move it on the week screen.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ onAdd(week, night) }, enabled = !already) { Text("Add") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** "From: Salt Fat Acid Heat, p. 123" (or Unknown book, or "Mum's recipes") with Edit source; an NYT recipe just
 *  says so. */
@Composable
private fun SourceLine(r: Recipe, onEdit: () -> Unit) {
    val kind = Sources.kind(r)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("From: " + Sources.label(r), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (kind != Sources.NYT) TextButton(onEdit) { Text(if (kind == Sources.BOOK && r.sourceTitle == null) "Add book" else "Edit source") }
    }
}

/**
 * Book (title with the book search, page; empty = Unknown book) or Other (a name like "Mum's recipes", required, with
 * the household's names as chips, and a note). Each kind keeps its own fields while switching; only the shown kind
 * is saved, so a book's author/ISBN never go with an other source.
 */
@Composable
fun EditSourceDialog(r: Recipe, books: List<BookSuggestion>, found: List<BookSuggestion>, saving: Boolean, error: String?,
                     onSave: (BookChoice, String) -> Unit, onDismiss: () -> Unit, onBookTyped: (String) -> Unit = {},
                     others: List<String> = emptyList(), onSaveOther: (String, String) -> Unit = { _, _ -> }) {
    val wasOther = Sources.kind(r) == Sources.OTHER
    var kind by rememberSaveable { mutableStateOf(if (wasOther) Sources.OTHER else Sources.BOOK) }
    // kept across rotation: the field's text, and the author/ISBN that came with a picked (or the saved) book
    var title by rememberSaveable { mutableStateOf(if (wasOther) "" else r.sourceTitle.orEmpty()) }
    var author by rememberSaveable { mutableStateOf(if (wasOther) null else r.sourceAuthor) }
    var isbn by rememberSaveable { mutableStateOf(if (wasOther) null else r.sourceIsbn) }
    var picked by rememberSaveable { mutableStateOf(!wasOther && BookChoice.saved(r.sourceTitle, r.sourceAuthor, r.sourceIsbn).picked) }
    var page by rememberSaveable { mutableStateOf(if (wasOther) "" else r.sourceRef.orEmpty()) }
    var name by rememberSaveable { mutableStateOf(if (wasOther) r.sourceTitle.orEmpty() else "") }
    var note by rememberSaveable { mutableStateOf(if (wasOther) r.sourceRef.orEmpty() else "") }
    val choice = BookChoice(title, author, isbn, picked)
    fun set(c: BookChoice) { title = c.title; author = c.author; isbn = c.isbn; picked = c.picked }
    val other = kind == Sources.OTHER
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where is it from?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceKindChips(kind, { kind = it })
                if (other) OtherFields(name, { name = it }, note, { note = it }, others)
                else BookFields(title, { t -> set(choice.typed(t)); onBookTyped(t) }, page, { page = it },
                    Books.suggest(books, found, choice), onPick = { set(it.choice) })
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton({ if (other) onSaveOther(name, note) else onSave(choice, page) },
                enabled = !saving && (!other || name.isNotBlank())) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
