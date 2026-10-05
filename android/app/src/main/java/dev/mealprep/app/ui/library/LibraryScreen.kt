package dev.mealprep.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.R
import dev.mealprep.app.core.RatingText
import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.ui.camera.refPrompt
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.TabHeader
import dev.mealprep.app.ui.common.graphViewModel
import java.time.LocalDate

@Composable
fun LibraryScreen(menu: List<Pair<String, Any>>, onOpen: (Any) -> Unit, onRecipe: (Int) -> Unit) {
    val vm = graphViewModel { g -> LibraryViewModel(g.repo) }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }
    Column(Modifier.fillMaxSize()) {
        TabHeader("Recipes", menu, onOpen)
        LibraryContent(state, vm::search, vm::sort, onRecipe, vm::load)
    }
}

@Composable
fun LibraryContent(
    state: LibraryState, onSearch: (String) -> Unit, onSort: (LibrarySort) -> Unit, onRecipe: (Int) -> Unit, onRetry: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(state.query, onSearch, label = { Text("Search recipes") }, singleLine = true,
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LibrarySort.entries.forEach { s -> FilterChip(state.sort == s, { onSort(s) }, { Text(s.label) }) }
        }
        OfflineBanner(state.offlineSince)
        MessageText(state.error)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!state.loading && state.all.isEmpty()) {
            if (state.error != null) TextButton(onRetry) { Text("Try again") }
            else Text("No recipes yet. Share one from NYT Cooking, or snap a cookbook page (⋮ menu).", Modifier.padding(vertical = 8.dp))
        } else if (state.shown.isEmpty() && state.query.isNotBlank()) {
            Text("No recipe titles match “${state.query.trim()}”.", Modifier.padding(vertical = 8.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            items(state.shown, key = { it.id }) { r ->
                RecipeRow(r, today) { onRecipe(r.id) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RecipeRow(r: Recipe, today: LocalDate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp)) {
        Text(r.title, style = MaterialTheme.typography.bodyLarge)
        val small = MaterialTheme.typography.bodySmall
        Text(RatingText.summary(r.ratings) ?: "Not rated yet", style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
        plannedText(r.plannedWeeks, today)?.let { Text(it, style = small) }
        refPrompt(r)?.let { Text("Uses page ${it.page}: add a photo of it so its ingredients are on the list.", style = small) }
    }
}
