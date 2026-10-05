package dev.mealprep.app.ui.home

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipDescription
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.nav.CardRoute
import dev.mealprep.app.ui.nav.RatingRoute
import java.time.LocalDate
import kotlinx.coroutines.flow.first

private const val PAGES = 27
private const val BACK = 4   // weeks reachable before this one

@Composable
fun HomeScreen(graph: AppGraph, startWeek: LocalDate?, onAction: (ContextAction) -> Unit, onOpen: (Any) -> Unit, menu: List<Pair<String, Any>>) {
    val vm = graphViewModel { g -> HomeViewModel(g.repo, g.imports) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val current = Weeks.weekStart(today)
    val initial = BACK + (startWeek?.let { Weeks.weeksBetween(today, it) } ?: 0).coerceIn(-BACK, PAGES - BACK - 1)
    val pager = rememberPagerState(initialPage = initial) { PAGES }
    val imports by vm.imports.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    AskForNotificationsOnce(graph)

    // Back on Home (or the app resumed days later): reload the visible week and re-read today's date.
    LifecycleResumeEffect(pager.currentPage) {
        today = LocalDate.now()
        vm.refresh(Weeks.weekStart(LocalDate.now()).plusWeeks((pager.currentPage - BACK).toLong()))
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Weeks.weekTitle(current.plusWeeks((pager.currentPage - BACK).toLong()), today),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { open = true }) { Text("Menu") }
                DropdownMenu(open, { open = false }) {
                    menu.forEach { (label, route) -> DropdownMenuItem({ Text(label) }, { open = false; onOpen(route) }) }
                }
            }
        }
        message?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MessageText(m); TextButton(onClick = vm::clearMessage) { Text("OK") }
            }
        }
        ImportCards(imports, onRetry = vm::retryImport, onDismiss = vm::dismissImport, onOpen = onOpen, onCancel = vm::cancelImport)
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            val week = current.plusWeeks((page - BACK).toLong())
            val ui by vm.week(week).collectAsStateWithLifecycle()
            WeekContent(ui, today, onAction, vm::place, vm::scale, vm::remove, onOpen, onRefresh = { vm.refresh(week) })
        }
    }
}

/** First time Home is shown: ask for POST_NOTIFICATIONS (import results arrive as notifications), remembering that we asked. */
@Composable
private fun AskForNotificationsOnce(graph: AppGraph) {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (shouldAskForNotifications(granted, graph.settingsStore.settings.first().askedNotificationPermission)) {
            graph.settingsStore.update { it.copy(askedNotificationPermission = true) }
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun ImportCards(
    imports: List<ImportUi>, onRetry: (java.util.UUID) -> Unit, onDismiss: (java.util.UUID) -> Unit, onOpen: (Any) -> Unit,
    onCancel: (java.util.UUID) -> Unit,
) {
    imports.forEach { i ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                when (i) {
                    is ImportUi.Waiting -> {
                        Text("Waiting for the home network — it will be sent automatically.")
                        Row { TextButton({ onCancel(i.id) }) { Text("Cancel") } }
                    }
                    is ImportUi.Reading -> { Text("Reading recipe…"); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is ImportUi.Failed -> {
                        Text(i.message)
                        if (!i.retrySafe) Text("This may have been added already — check the week before trying again.")
                        Row {
                            if (i.retrySafe) TextButton({ onRetry(i.id) }) { Text("Try again") }
                            TextButton({ onDismiss(i.id) }) { Text("Dismiss") }
                        }
                    }
                    is ImportUi.Done -> {
                        Text(if (i.existing) "Already in your library: ${i.title}" else "Added ${i.title}", fontWeight = FontWeight.Bold)
                        i.ratingLine?.let { Text(it) }
                        Text("Week of ${Weeks.shortDate(i.week)} — it's in the tray until you put it on a night.")
                        Row { TextButton({ onDismiss(i.id) }) { Text("OK") } }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeekContent(
    ui: WeekUi, today: LocalDate,
    onAction: (ContextAction) -> Unit,
    onPlace: (PlanEntry, Int?) -> Unit,
    onScale: (PlanEntry, Double) -> Unit,
    onRemove: (PlanEntry) -> Unit,
    onOpen: (Any) -> Unit,
    onRefresh: () -> Unit,
) {
    var sheetFor by remember { mutableStateOf<PlanEntry?>(null) }
    val all = ui.view?.all.orEmpty()
    val dropTo: (Int?) -> (Int) -> Unit = { day -> { id -> all.firstOrNull { it.id == id }?.let { onPlace(it, day) } } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OfflineBanner(ui.offlineSince); MessageText(ui.error) }
        item { StatusStripRow(ui.strip) }
        item { Button(onClick = { onAction(ui.action) }, modifier = Modifier.fillMaxWidth()) { Text(ui.action.label) } }
        val view = ui.view
        if (view != null) {
            item {
                Column(Modifier.fillMaxWidth().dropTarget(dropTo(null))) {
                    Text("Not on a night yet", style = MaterialTheme.typography.titleSmall)
                    if (view.unplaced.isEmpty()) Text("Shared recipes land here.", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { view.unplaced.forEach { EntryChip(it) { sheetFor = it } } }
                }
            }
            items(view.nights, key = { it.day }) { n ->
                Row(Modifier.fillMaxWidth().dropTarget(dropTo(n.day)).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Weeks.nightTitle(view.week, n.day), Modifier.width(64.dp),
                        fontWeight = if (n.date == today) FontWeight.Bold else FontWeight.Normal)
                    if (n.entries.isEmpty()) Text("—", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { n.entries.forEach { EntryChip(it) { sheetFor = it } } }
                }
            }
        } else if (ui.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { TextButton(onClick = onRefresh) { Text("Refresh") } }
    }
    sheetFor?.let { e ->
        EntryDialog(e, onDismiss = { sheetFor = null },
            onPlace = { d -> sheetFor = null; onPlace(e, d) },
            onScale = { m -> sheetFor = null; onScale(e, m) },
            onRemove = { sheetFor = null; onRemove(e) },
            onOpen = { r -> sheetFor = null; onOpen(r) })
    }
}

@Composable
private fun StatusStripRow(s: StatusStrip) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Planned" to s.planned, "Cart sent" to s.cartSent, "Prep done" to s.prepDone).forEach { (label, done) ->
            Text((if (done) "✓ " else "○ ") + label,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryChip(e: PlanEntry, onClick: () -> Unit) {
    val label = (e.title ?: "Recipe ${e.recipeId}") + if (e.multiplier != 1.0) " ×${fmt(e.multiplier)}" else ""
    AssistChip(onClick = onClick, label = { Text(label) },
        modifier = Modifier.dragAndDropSource { _ -> DragAndDropTransferData(ClipData.newPlainText("entry", e.id.toString())) })
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.dropTarget(onDrop: (Int) -> Unit): Modifier = dragAndDropTarget(
    shouldStartDragAndDrop = { it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) },
    target = object : DragAndDropTarget {
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val id = event.toAndroidDragEvent().clipData.getItemAt(0).text.toString().toIntOrNull() ?: return false
            onDrop(id); return true
        }
    },
)

private fun fmt(m: Double) = if (m % 1.0 == 0.0) m.toInt().toString() else m.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDialog(e: PlanEntry, onDismiss: () -> Unit, onPlace: (Int?) -> Unit, onScale: (Double) -> Unit, onRemove: () -> Unit, onOpen: (Any) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title ?: "Recipe") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Put on")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { d -> FilterChip(e.day == d, { onPlace(d) }, { Text(Weeks.dayLabel(d)) }) }
                    FilterChip(e.day == null, { onPlace(null) }, { Text("No night") })
                }
                Text("Make")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.5, 1.0, 1.5, 2.0).forEach { m -> FilterChip(e.multiplier == m, { onScale(m) }, { Text("×${fmt(m)}") }) }
                }
                if (e.day != null) Row {
                    TextButton({ onOpen(CardRoute(e.id)) }) { Text("Cook card") }
                    TextButton({ onOpen(RatingRoute(e.id, e.week)) }) { Text(if (e.rating != null) "Rating: ${e.rating.family}/5" else "Rate") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = { TextButton(onClick = onRemove) { Text("Remove from week") } },
    )
}
