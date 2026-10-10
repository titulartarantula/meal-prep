package dev.mealprep.app.ui.exchange

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mealprep.app.core.CopyResult
import dev.mealprep.app.data.api.ImportItem
import dev.mealprep.app.data.api.ImportReport
import dev.mealprep.app.graph
import dev.mealprep.app.ui.camera.PagesState
import dev.mealprep.app.ui.common.AdviceText
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomActionBar
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.announced
import dev.mealprep.app.ui.nav.ImportRoute
import java.io.File
import kotlinx.coroutines.launch

const val IMPORT_TITLE = "Import recipes"
const val SHOW_IN_RECIPES = "Show in Recipes"
const val CHOOSE_ANOTHER = "Choose another file"
const val AI_NOTE = "Ingredient lines from other apps and documents are tidied by the AI as each recipe is added (about " +
    "half a minute each). You can leave this screen meanwhile."
const val READ_NOTE = "Finding the recipes in a document takes a while: about half a minute for each part. If you leave, " +
    "open the file again later: the server keeps reading it."
const val SELECT_ALL = "Select all"
const val SELECT_NONE = "Select none"
const val LEAVE_NOTE = "You can leave this screen: the recipes keep coming in, and Meal Prep tells you when it's done."

const val DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val MIXED_FILES = "Choose one kind at a time: either photos of one recipe, or one recipe file."
const val MANY_DOCUMENTS = "Choose one file at a time. A document can hold several recipes; you pick which to keep."
const val MANY_PHOTOS = "A recipe can have up to ${PagesState.MAX_PAGES} pages. Choose ${PagesState.MAX_PAGES} photos or fewer."

/** What the system file picker offers: recipe documents (PDF, Word, text), recipe files, saved web pages, and photos of
 *  a recipe's pages (from Files and cloud providers such as OneDrive, which the system photo picker can't see). Some
 *  providers type a .json as octet-stream or plain text; the server reads the content, so a wrong type only costs a
 *  clear message. */
val PICK_TYPES = arrayOf("application/pdf", DOCX_TYPE, "text/plain", "application/json", "application/ld+json", "text/html",
    "application/octet-stream", "image/*")

/** What a pick in the file picker is: photos (the pages of one recipe, in the order picked) go to the photo review like
 *  Choose photos; a file goes to the import preview, which takes one file at a time. */
sealed interface RecipePickerSelection {
    data class Photos(val uris: List<Uri>) : RecipePickerSelection
    data class Document(val uri: Uri) : RecipePickerSelection
    data class Error(val message: String) : RecipePickerSelection
}

fun recipePickerSelection(uris: List<Uri>, isPhoto: (Uri) -> Boolean): RecipePickerSelection? {
    if (uris.isEmpty()) return null
    val photos = uris.filter(isPhoto)
    return when {
        photos.size == uris.size && uris.size > PagesState.MAX_PAGES -> RecipePickerSelection.Error(MANY_PHOTOS)
        photos.size == uris.size -> RecipePickerSelection.Photos(uris)
        photos.isNotEmpty() -> RecipePickerSelection.Error(MIXED_FILES)
        uris.size > 1 -> RecipePickerSelection.Error(MANY_DOCUMENTS)
        else -> RecipePickerSelection.Document(uris.single())
    }
}

/**
 * Opens the system file picker (several files allowed). Photos go to [onPhotos] (the photo review copies them in); a
 * file is copied in at once (its read grant is short-lived) and goes to [onDocument], as does a pick that can't be used.
 */
@Composable
fun rememberRecipeAndPhotoFilePicker(
    onPhotos: (List<Uri>) -> Unit, onDocument: (CopyResult) -> Unit,
): () -> Unit {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            // Each file's type and name come from its provider (a query, slow for a cloud one): off the main thread.
            when (val selection = g.pickedFiles(uris)) {
                is RecipePickerSelection.Photos -> onPhotos(selection.uris)
                is RecipePickerSelection.Document -> onDocument(g.copyImport(selection.uri))
                is RecipePickerSelection.Error -> onDocument(CopyResult.Err(selection.message))
                null -> {}
            }
        }
    }
    return { runCatching { launcher.launch(PICK_TYPES) } }
}

class ImportActions(
    val onBack: () -> Unit = {}, val onToggle: (String) -> Unit = {}, val onApply: () -> Unit = {},
    val onRetry: () -> Unit = {}, val onPickAnother: () -> Unit = {}, val onRecipe: (Int) -> Unit = {},
    val onDone: () -> Unit = {}, val onRefresh: () -> Unit = {}, val onSelectAll: (Boolean) -> Unit = {},
)

@Composable
fun ImportScreen(route: ImportRoute, onBack: () -> Unit, onRecipe: (Int) -> Unit, onDone: () -> Unit,
                 onPhotos: (List<Uri>) -> Unit) {
    val g = LocalContext.current.graph
    val vm: ImportViewModel = viewModel(key = "import-${route.path}-${route.jobId}", factory = viewModelFactory {
        initializer {
            ImportViewModel(g.repo, route.path.ifBlank { null }?.let(::File), route.name, route.error, route.jobId,
                createSavedStateHandle(), watch = { id -> g.jobs.watchImport(id) })
        }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val pick = rememberRecipeAndPhotoFilePicker(onPhotos = onPhotos, onDocument = { r ->
        when (r) {
            is CopyResult.Ok -> vm.newFile(r.value.file, r.value.name)
            is CopyResult.Err -> vm.fileError(r.message)
        }
    })
    ImportContent(state, ImportActions(onBack = onBack, onToggle = vm::toggle, onApply = vm::apply, onRetry = vm::preview,
        onPickAnother = pick, onRecipe = onRecipe, onDone = onDone, onRefresh = vm::refresh, onSelectAll = vm::selectAll))
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall,
    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() })

@Composable
fun ImportContent(state: ImportState, actions: ImportActions) {
    val job = state.job
    Column(Modifier.fillMaxSize()) {
        BackTopBar(IMPORT_TITLE, actions.onBack, subtitle = state.name.ifBlank { null })
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            OfflineBanner(state.offlineSince)
            when {
                job != null -> JobBody(job, state.jobError, actions)
                state.loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Reading ${state.name.ifBlank { "the file" }}…", Modifier.padding(top = 8.dp).announced())
                    val reading = state.reading
                    val part = reading?.let(ImportLogic::readProgress)
                    if (reading != null && part != null) {
                        Text(part, Modifier.announced(), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator({ reading.progress.done.toFloat() / reading.progress.total }, Modifier.fillMaxWidth())
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (reading != null) {
                        Text(READ_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.error != null -> Column {
                    MessageText(state.error)
                    if (state.canRetry) TextButton(actions.onRetry, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                    TextButton(actions.onPickAnother, Modifier.heightIn(min = 48.dp)) { Text(CHOOSE_ANOTHER) }
                }
                state.report != null -> PreviewBody(state.report, state.ticks, actions.onToggle, actions.onSelectAll)
                state.jobError != null -> Column {
                    MessageText(state.jobError)
                    TextButton(actions.onRefresh, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                }
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())   // opening a job
            }
        }
        // The main action, docked in thumb reach (0.8.0 layout).
        when {
            job != null -> BottomActionBar {
                if (ImportLogic.running(job)) OutlinedButton(actions.onDone, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(SHOW_IN_RECIPES) }
                else Button(actions.onDone, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(SHOW_IN_RECIPES) }
            }
            state.report != null && state.error == null -> BottomActionBar(above = {
                state.applyError?.let { MessageText(it) }
                if (state.starting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }) {
                Button(actions.onApply, enabled = state.canApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(state.buttonLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewBody(report: ImportReport, ticks: Set<String>, onToggle: (String) -> Unit, onSelectAll: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(ImportLogic.summary(report), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp).semantics { heading() })
        }
        if (ImportLogic.offerSelectAll(report)) item(key = "select-all") {
            val all = ImportLogic.selectableKeys(report)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ onSelectAll(true) }, Modifier.heightIn(min = 48.dp), enabled = !ticks.containsAll(all)) { Text(SELECT_ALL) }
                TextButton({ onSelectAll(false) }, Modifier.heightIn(min = 48.dp), enabled = ticks.any { it in all }) { Text(SELECT_NONE) }
            }
        }
        items(report.warnings) { w -> AdviceText("⚠ $w", Modifier.padding(vertical = 4.dp)) }
        if (report.items.any { it.key in ticks && it.aiTidy }) item {
            Text(AI_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ImportSection.entries.forEach { sec ->
            val rows = report.items.filter { ImportLogic.section(it) == sec }
            if (rows.isNotEmpty()) {
                item(key = "h-$sec") { SectionTitle("${sec.title} (${rows.size})") }
                items(rows, key = { it.key }) { i ->
                    PreviewRow(i, i.key in ticks) { onToggle(i.key) }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A recipe of the file. Tickable rows are one checkbox (the whole row, ≥ 48 dp); the others say why in words. */
@Composable
private fun PreviewRow(i: ImportItem, checked: Boolean, onToggle: () -> Unit) {
    val selectable = ImportLogic.selectable(i)
    val whole = if (selectable) Modifier.toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
        else Modifier.semantics(mergeDescendants = true) {}
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(whole).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            if (selectable) Checkbox(checked, onCheckedChange = null)
        }
        ItemText(i, ImportLogic.tickLabel(i), ImportLogic.detail(i), failed = i.status == "failed")
    }
}

@Composable
private fun ItemText(i: ImportItem, label: String?, detail: String?, failed: Boolean = false) {
    Column(Modifier.padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(ImportLogic.title(i), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        ImportLogic.matchLine(i)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        i.reasons.forEach { r ->
            Text("⚠ $r", style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The job: "Adding 3 of 12…" while it runs, then what happened, with the added recipes to open. */
@Composable
private fun JobBody(job: ImportReport, error: String?, actions: ImportActions) {
    val running = ImportLogic.running(job)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (running) {
                    Text(ImportLogic.progress(job), Modifier.announced(), style = MaterialTheme.typography.titleMedium)
                    val total = job.progress.total.coerceAtLeast(1)
                    LinearProgressIndicator({ job.progress.done.toFloat() / total }, Modifier.fillMaxWidth())
                    Text(LEAVE_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(ImportLogic.result(job), Modifier.semantics { heading() }.announced(), style = MaterialTheme.typography.titleMedium)
                    ImportLogic.jobError(job)?.let { MessageText(it) }
                }
                error?.let { MessageText(it) }
            }
        }
        resultGroup("Added", job.items.filter { it.status == "added" || it.status == "updated" }, actions.onRecipe) {
            if (it.status == "updated") "Replaced with the file's version" else null
        }
        resultGroup("Being added", job.items.filter { it.status == "pending" }, null) { null }
        resultGroup("Couldn't be added", job.items.filter { it.status == "failed" }, null) { null }
        resultGroup("Already in Recipes", job.items.filter { it.status == "duplicate" }, actions.onRecipe) { null }
    }
}

private fun LazyListScope.resultGroup(title: String, rows: List<ImportItem>, onRecipe: ((Int) -> Unit)?, label: (ImportItem) -> String?) {
    if (rows.isEmpty()) return
    item(key = "g-$title") { SectionTitle("$title (${rows.size})") }
    items(rows, key = { "$title-${it.key}" }) { i ->
        val id = i.recipeId ?: i.match?.recipeId
        val open = if (onRecipe != null && id != null) Modifier.clickable(onClickLabel = "Open the recipe", role = Role.Button) { onRecipe(id) }
            else Modifier.semantics(mergeDescendants = true) {}
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(open).padding(vertical = 8.dp, horizontal = 4.dp)) {
            ItemText(i, label(i), detail = null, failed = i.status == "failed")
        }
        HorizontalDivider()
    }
}
