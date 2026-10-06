package dev.mealprep.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import dev.mealprep.app.ui.common.GardenChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.R
import dev.mealprep.app.core.RatingText
import dev.mealprep.app.core.Sources
import androidx.compose.ui.text.style.TextOverflow
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.ui.camera.PagesState
import dev.mealprep.app.ui.camera.refPrompt
import dev.mealprep.app.ui.exchange.ExportHost
import dev.mealprep.app.ui.home.ImportCards
import dev.mealprep.app.ui.nav.CameraRoute
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.TabHeader
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate

/**
 * The Recipes tab: the library, and where recipes come in ("Add recipe": camera, photos, an NYT link; shares from
 * other apps land here too). [onPhotos] gets the picked images, [onLink] a pasted NYT link (saved in the background).
 */
@Composable
fun LibraryScreen(
    menu: List<Pair<String, Any>>, onOpen: (Any) -> Unit, onRecipe: (Int) -> Unit,
    onPhotos: (List<Uri>) -> Unit, onLink: (String) -> Unit,
) {
    val vm = graphViewModel { g -> LibraryViewModel(g.repo, g.imports, hidden = g.hiddenImports) }
    val state by vm.state.collectAsStateWithLifecycle()
    val imports by vm.imports.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }
    val ctx = LocalContext.current
    var paste by remember { mutableStateOf<String?>(null) }   // non-null: the dialog is open (value = copied link or "")
    var exporting by rememberSaveable { mutableStateOf(false) }
    // More options: the library's own items first (they act here), then Staples and Settings (screens).
    val menuHere = libraryMenu(menu)
    val openHere: (Any) -> Unit = { r -> if (r == LibraryMenu.EXPORT_ALL) exporting = true else onOpen(r) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PagesState.MAX_PAGES)) { uris ->
        if (uris.isNotEmpty()) onPhotos(uris)
    }
    // TalkBack order: the header, then Add recipe, then the list (as last item it came after every recipe).
    Box(Modifier.fillMaxSize().semantics { isTraversalGroup = true }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.semantics { isTraversalGroup = true; traversalIndex = 0f }) { TabHeader("Recipes", menuHere, openHere) }
            Column(Modifier.weight(1f).semantics { isTraversalGroup = true; traversalIndex = 2f }) {
                ImportCards(imports, onRetry = vm::retryImport, onDismiss = vm::dismissImport, onOpen = onOpen, onCancel = vm::cancelImport)
                LibraryContent(state, vm::search, vm::sort, onRecipe, vm::load, onSource = vm::source, onCompany = vm::company)
            }
        }
        AddRecipeButton(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).semantics { traversalIndex = 1f }, onPick = { w ->
            when (w) {
                AddWay.CAMERA -> onOpen(CameraRoute())
                AddWay.PHOTOS -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                AddWay.LINK -> paste = clipboardNytLink(ctx) ?: ""
            }
        })
    }
    if (exporting) ExportHost(recipeId = null, onClose = { exporting = false })
    paste?.let { copied ->
        PasteLinkDialog(copied.ifEmpty { null }, onSave = { url -> paste = null; onLink(url) }, onDismiss = { paste = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryContent(
    state: LibraryState, onSearch: (String) -> Unit, onSort: (LibrarySort) -> Unit, onRecipe: (Int) -> Unit, onRetry: () -> Unit,
    today: LocalDate = LocalDate.now(), onSource: (String) -> Unit = {}, onCompany: (Boolean) -> Unit = {},
) {
    var filters by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // Only the search stays: sort and filters fold away under its Filters button, and one line says what's in effect
        // (at large text the chips took 40 % of the screen).
        OutlinedTextField(state.query, onSearch, label = { Text("Search recipes") }, singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon = {
                IconButton({ filters = !filters },
                    colors = if (filters) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors()) {
                    Icon(painterResource(R.drawable.ic_tune), contentDescription = if (filters) HIDE_FILTERS else SHOW_FILTERS)
                }
            },
            modifier = Modifier.fillMaxWidth())
        if (filters) {
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                // The sort is one choice of three (radio buttons); Good for company is a toggle on its own (a checkbox).
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LibrarySort.entries.forEach { s -> GardenChip(state.sort == s, { onSort(s) }, s.label) }
                }
                GardenChip(state.company, { onCompany(!state.company) }, "Good for company", toggle = true)
            }
            if (state.all.isNotEmpty()) SourceFilter(state.sources, state.source, onSource)
        } else Text(filterSummary(state), Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OfflineBanner(state.offlineSince)
        MessageText(state.error)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.all.isEmpty()) {
            if (state.error != null) TextButton(onRetry) { Text("Try again") }
            else Text(EMPTY_LIBRARY, Modifier.padding(vertical = 8.dp))
        } else if (state.shown.isEmpty() && state.query.isNotBlank()) {
            Text("No recipe titles match “${state.query.trim()}”.", Modifier.padding(vertical = 8.dp))
        } else if (state.shown.isEmpty() && state.company) {
            Text(NO_COMPANY, Modifier.padding(vertical = 8.dp))
        } else if (state.shown.isEmpty() && state.source != Sources.ALL) {
            Text("No recipes from that source.", Modifier.padding(vertical = 8.dp))
        }
        // Bottom padding: the last row can scroll clear of the Add recipe button.
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 88.dp)) {
            items(state.shown, key = { it.id }) { r ->
                RecipeRow(r, today) { onRecipe(r.id) }
                HorizontalDivider()
            }
        }
    }
}

/** The Recipes tab's own More options items (handled on the tab, not routes). */
enum class LibraryMenu { EXPORT_ALL }

const val EXPORT_ALL_MENU = "Export all recipes (a backup file)"

/** The Recipes tab's More options: its own items, then the main screens' (Staples, Settings). */
fun libraryMenu(main: List<Pair<String, Any>>): List<Pair<String, Any>> = listOf(EXPORT_ALL_MENU to LibraryMenu.EXPORT_ALL) + main

const val NO_COMPANY = "None rated good for company yet. After dinner, rate it and answer “Yes” to “Would you make it for company?”."

const val EMPTY_LIBRARY = "No recipes yet. Tap Add recipe to scan a cookbook page, choose photos or paste an NYT " +
    "Cooking link — or share a recipe from the NYT Cooking app."

const val SHOW_FILTERS = "Sort and filter"
const val HIDE_FILTERS = "Hide sort and filters"

/** What the folded filters do now: "Newest first · all sources", "A–Z · NYT Cooking · good for company". */
fun filterSummary(state: LibraryState): String = listOfNotNull(
    when (state.sort) { LibrarySort.NEWEST -> "Newest first"; LibrarySort.FAVOURITES -> "Favourites first"; LibrarySort.AZ -> "A–Z" },
    if (state.source == Sources.ALL) "all sources" else state.sources.firstOrNull { it.key == state.source }?.label ?: "one source",
    if (state.company) "good for company" else null,
).joinToString(" · ")

/** A library row's one quiet line: "★ 4.5 · good for company · Invented Pantry Book, p. 88" (or "Not rated · …"). */
fun rowDetail(r: Recipe): String = listOfNotNull(r.ratings.avgFamily?.let { "★ ${RatingText.number(it)}" } ?: "Not rated",
    RatingText.imported(r.ratings), if (r.ratings.company == "yes") "good for company" else null, Sources.label(r)).joinToString(" · ")

/** [rowDetail] for TalkBack: "Family 4.5 out of 5, good for company, …" (not "black star 4.5"). */
fun spokenRowDetail(r: Recipe): String = listOfNotNull(r.ratings.avgFamily?.let { "Family ${RatingText.number(it)} out of 5" } ?: "Not rated",
    RatingText.imported(r.ratings, spoken = true), if (r.ratings.company == "yes") "good for company" else null, Sources.label(r)).joinToString(", ")

/** Title, then one quiet line; "On the plan: …" and a missing page only when they apply. */
@Composable
private fun RecipeRow(r: Recipe, today: LocalDate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp)) {
        Text(r.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(rowDetail(r), Modifier.semantics { contentDescription = spokenRowDetail(r) }, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val label = MaterialTheme.typography.labelMedium
        plannedText(r.plannedWeeks, today)?.let { Text(it, style = label, color = MaterialTheme.colorScheme.primary) }
        refPrompt(r)?.let {
            Text("⚠ Page ${it.page} missing", Modifier.semantics { contentDescription = "Page ${it.page} missing: its ingredients aren't on the list" },
                style = label, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** One line, "From: All sources ▾" (like the shopping list's week line): NYT Cooking, each book and named other source, Unknown book, Other. */
@Composable
private fun SourceFilter(options: List<Sources.Option>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.key == selected } ?: options.first()
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Choose a source", role = Role.DropdownList) { open = true },
            verticalAlignment = Alignment.CenterVertically) {
            Text("From:", style = MaterialTheme.typography.bodyLarge)
            Text(current.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
            Icon(painterResource(R.drawable.ic_arrow_drop_down), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { o ->
                DropdownMenuItem({ Text("${o.label} (${o.count})") }, { open = false; onSelect(o.key) },
                    trailingIcon = if (o.key == current.key) { { Text("✓") } } else null)
            }
        }
    }
}
