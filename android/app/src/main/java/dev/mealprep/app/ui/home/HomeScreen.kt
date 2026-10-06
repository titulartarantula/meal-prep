package dev.mealprep.app.ui.home

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipDescription
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.R
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.PlanEntry
import dev.mealprep.app.ui.nav.DraftRoute
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.OfflineBanner
import dev.mealprep.app.ui.common.OverflowMenu
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.nav.CardRoute
import dev.mealprep.app.ui.nav.RatingRoute
import dev.mealprep.app.ui.nav.RecipeRoute
import dev.mealprep.app.ui.camera.RefPrompt
import dev.mealprep.app.ui.camera.refPromptText
import dev.mealprep.app.work.ImportWorker
import java.time.LocalDate
import kotlinx.coroutines.flow.first

private const val BACK = Weeks.PAST   // weeks reachable before this one

@Composable
fun HomeScreen(graph: AppGraph, startWeek: LocalDate?, onAction: (ContextAction) -> Unit, onOpen: (Any) -> Unit, menu: List<Pair<String, Any>>) {
    val vm = graphViewModel { g -> HomeViewModel(g.repo, g.imports, hidden = g.hiddenImports) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val current = Weeks.weekStart(today)
    // The planning horizon: this week + the next 3, further only to a later week that already has recipes or that
    // a link opens. Past weeks stay reachable.
    val ahead by vm.ahead.collectAsStateWithLifecycle()
    val span = pagerSpan(ahead, startWeek?.let { Weeks.weeksBetween(today, it) } ?: 0)
    val pager = rememberPagerState(initialPage = span.initial) { span.pages }
    val imports by vm.imports.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    AskForNotificationsOnce(graph)
    val drag = remember { WeekDrag() }

    // Back on Home (or the app resumed days later): reload the visible week and re-read today's date.
    LifecycleResumeEffect(pager.currentPage) {
        today = LocalDate.now()
        vm.refreshAhead()
        vm.refresh(Weeks.weekStart(LocalDate.now()).plusWeeks((pager.currentPage - BACK).toLong()))
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Weeks.weekTitle(current.plusWeeks((pager.currentPage - BACK).toLong()), today),
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
            OverflowMenu(menu, onOpen)
        }
        message?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MessageText(m); TextButton(onClick = vm::clearMessage) { Text("OK") }
            }
        }
        ImportCards(imports, onRetry = vm::retryImport, onDismiss = vm::dismissImport, onOpen = onOpen, onCancel = vm::cancelImport)
        // While a recipe is held the drag has priority: the week can't be swiped away underneath it.
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = !drag.dragging) { page ->
            val week = current.plusWeeks((page - BACK).toLong())
            val ui by vm.week(week).collectAsStateWithLifecycle()
            WeekContent(ui, today, onAction, vm::place, vm::scale, vm::remove, onOpen, onRefresh = { vm.refresh(week) },
                loadRef = vm::refPromptFor, drag = drag)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportCards(
    imports: List<ImportUi>, onRetry: (java.util.UUID) -> Unit, onDismiss: (java.util.UUID) -> Unit, onOpen: (Any) -> Unit,
    onCancel: (java.util.UUID) -> Unit,
) {
    if (imports.isEmpty()) return
    // At most about a third of the screen: several cards (or large text) must not push the week or the list away.
    val max = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.35f).toDp() }
    Column(Modifier.heightIn(max = max).verticalScroll(rememberScrollState())) { imports.forEach { i ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp)) {
                when (i) {
                    is ImportUi.Waiting -> {
                        Text("Waiting for the home network — it will be sent automatically.")
                        Row { TextButton({ onCancel(i.id) }) { Text("Cancel") } }
                    }
                    is ImportUi.Reading -> { Text(readingText(i.kind)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    is ImportUi.Failed -> {
                        Text(if (i.kind == ImportWorker.PAGES) "Couldn't add the page" else "Couldn't add the recipe", fontWeight = FontWeight.Bold)
                        Text(i.message)
                        if (!i.retrySafe) Text(if (i.kind == ImportWorker.PAGES) "Check the shopping list for its ingredients before adding it again."
                            else "It may have been saved already — check Recipes before trying again.")
                        Row {
                            if (i.retrySafe) TextButton({ onRetry(i.id) }) { Text("Try again") }
                            TextButton({ onDismiss(i.id) }) { Text("Dismiss") }
                        }
                    }
                    is ImportUi.Done -> {
                        Text(doneTitle(i.title, i.existing), fontWeight = FontWeight.Bold)
                        i.ratingLine?.let { Text(it) }
                        // Imports queued by 0.4.1 and earlier also went into a week.
                        i.week?.let { Text("Also in the week of ${Weeks.shortDate(it)}, in the tray until you put it on a night.") }
                        i.ref?.let { RefLine(it) }
                        FlowRow {
                            if (i.week == null) TextButton({ onOpen(RecipeRoute(i.recipeId, addToWeek = true)) }) { Text("Add to a week…") }
                            i.ref?.let { r -> TextButton({ onOpen(refRoute(r)) }) { Text("Add photo of p.${r.page}") } }
                            TextButton({ onDismiss(i.id) }) { Text(if (i.ref != null) "Not now" else "OK") }
                        }
                    }
                    is ImportUi.PageAdded -> {
                        Text("Added the page to ${i.title}", fontWeight = FontWeight.Bold)
                        val next = i.next
                        if (next == null) Text("Its ingredients are on the shopping list now.") else RefLine(next)
                        FlowRow {
                            next?.let { r -> TextButton({ onOpen(refRoute(r)) }) { Text("Add photo of p.${r.page}") } }
                            TextButton({ onDismiss(i.id) }) { Text(if (next != null) "Not now" else "OK") }
                        }
                    }
                }
            }
        }
    } }
}

/** "This uses “Batter for 24 crêpes, page 191” — add a photo of page 191?" + why it matters. */
@Composable
private fun RefLine(p: RefPrompt) {
    Text(refPromptText(p))
    Text("Until then its ingredients aren't on the shopping list.", style = MaterialTheme.typography.bodySmall)
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
    /** The recipe's first page reference without a photo (null if none, or offline). */
    loadRef: suspend (recipeId: Int) -> RefPrompt? = { null },
    drag: WeekDrag = remember { WeekDrag() },
) {
    var sheetFor by remember { mutableStateOf<PlanEntry?>(null) }
    val all = ui.view?.all.orEmpty()
    val drop: (Slot, String?) -> Boolean = { slot, text -> dropMove(all, text, slot)?.let { (e, d) -> onPlace(e, d); true } ?: false }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { OfflineBanner(ui.offlineSince); MessageText(ui.error) }
        item { StatusStripRow(ui.strip, onCart = ui.sentDraftId?.let { id -> { onOpen(DraftRoute(id)) } }) }
        when {
            ui.loading && ui.view == null -> {}   // the first load: no button until we know what the week needs
            ui.action == ContextAction.AddRecipes -> item { EmptyWeek { onAction(ui.action) } }
            else -> item { Button(onClick = { onAction(ui.action) }, modifier = Modifier.fillMaxWidth()) { Text(ui.action.label) } }
        }
        val view = ui.view
        if (view != null) {
            item {
                Column(Modifier.fillMaxWidth().dropZone(drag, Slot.TRAY, drop).padding(4.dp)) {
                    Text("Not on a night yet", style = MaterialTheme.typography.titleSmall)
                    DropHint(drag, Slot.TRAY, "Drop here to take it off its night")
                    if (view.unplaced.isEmpty()) Text("Recipes you add to this week wait here until you put them on a night.",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { view.unplaced.forEach { EntryChip(it) { sheetFor = it } } }
                }
            }
            if (all.isNotEmpty()) item {
                Text("Hold a recipe and drag it to a night (or back here), or tap it to move it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(view.nights, key = { it.day }) { n ->
                val slot = Slot(n.day)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).dropZone(drag, slot, drop).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    // At least 64 dp so the nights line up; wider (never wrapped) with large text.
                    Text(Weeks.nightTitle(view.week, n.day), Modifier.widthIn(min = 64.dp).padding(end = 8.dp), softWrap = false,
                        fontWeight = if (n.date == today) FontWeight.Bold else FontWeight.Normal)
                    Column {
                        DropHint(drag, slot, "Drop here")
                        if (n.entries.isEmpty() && drag.over != slot) Text("—", style = MaterialTheme.typography.bodySmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { n.entries.forEach { EntryChip(it) { sheetFor = it } } }
                    }
                }
            }
        } else if (ui.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { TextButton(onClick = onRefresh) { Text("Refresh") } }
    }
    sheetFor?.let { e ->
        val ref by produceState<RefPrompt?>(null, e.recipeId) { value = loadRef(e.recipeId) }
        EntryDialog(e, ref, onDismiss = { sheetFor = null },
            onPlace = { d -> sheetFor = null; onPlace(e, d) },
            onScale = { m -> sheetFor = null; onScale(e, m) },
            onRemove = { sheetFor = null; onRemove(e) },
            onOpen = { r -> sheetFor = null; onOpen(r) })
    }
}

/** Nothing planned: weekly planning picks recipes from the library (Recipes tab). */
@Composable
private fun EmptyWeek(onRecipes: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(EMPTY_WEEK, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRecipes, modifier = Modifier.fillMaxWidth()) { Text(ContextAction.AddRecipes.label) }
        }
    }
}

const val EMPTY_WEEK = "Nothing planned yet — add recipes from your Recipes."

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusStripRow(s: StatusStrip, onCart: (() -> Unit)?) {
    // Wraps whole steps onto a new line with large text, never "Prep" / "done" split across lines.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        listOf("Planned" to s.planned, "Cart sent" to s.cartSent, "Prep done" to s.prepDone).forEach { (label, done) ->
            val text = (if (done) "✓ " else "○ ") + label
            val color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            // A sent cart can be reopened (to open it in Loblaws again).
            if (label == "Cart sent" && onCart != null) TextButton(onClick = onCart) { Text(text, color = color, softWrap = false) }
            else Text(text, color = color, softWrap = false)
        }
    }
}

/**
 * A recipe on the week. Tap: the entry dialog (move, scale, remove). Hold: pick it up (a buzz) and drag it to a
 * night or the tray.
 *
 * One gesture detector does both. Until 0.3.0 the chip was an AssistChip (its own click handler) with
 * `dragAndDropSource` around it: the click handler consumed the touch, which cancels the drag source's hold
 * detection, so a hold never started a drag. Stacking them the other way round loses the tap instead.
 */
@Suppress("DEPRECATION")   // the block form is the one that lets a single detector start the transfer on a hold
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryChip(e: PlanEntry, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val label = (e.title ?: "Recipe ${e.recipeId}") + if (e.multiplier != 1.0) " ×${fmt(e.multiplier)}" else ""
    Surface(
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.minimumInteractiveComponentSize()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Open, move or remove") { click(); true }
            }
            .dragAndDropSource(block = {
                detectTapGestures(
                    onTap = { click() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        startTransfer(DragAndDropTransferData(ClipData.newPlainText("Meal Prep recipe", WeekDragText.of(e.id))))
                    },
                )
            }),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A night or the tray as a drop zone: outlined while any recipe is held, filled when it's under the finger. */
@Composable
private fun Modifier.dropZone(drag: WeekDrag, slot: Slot, onDrop: (Slot, String?) -> Boolean): Modifier {
    val drop by rememberUpdatedState(onDrop)
    val target = remember(drag, slot) {
        object : DragAndDropTarget {
            override fun onStarted(event: DragAndDropEvent) = drag.started()
            override fun onEntered(event: DragAndDropEvent) = drag.entered(slot)
            override fun onExited(event: DragAndDropEvent) = drag.exited(slot)
            override fun onEnded(event: DragAndDropEvent) = drag.ended()
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData
                return drop(slot, clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString())
            }
        }
    }
    val shape = MaterialTheme.shapes.small
    val look = when {
        drag.over == slot -> Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape)
            .border(2.dp, MaterialTheme.colorScheme.primary, shape)
        drag.dragging -> Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
        else -> Modifier
    }
    return this.then(look).dragAndDropTarget(
        shouldStartDragAndDrop = { it.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN) },
        target = target,
    )
}

/** Says where a drop goes (the zone's colour alone isn't enough). */
@Composable
private fun DropHint(drag: WeekDrag, slot: Slot, text: String) {
    if (drag.over == slot) Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
}

private fun fmt(m: Double) = if (m % 1.0 == 0.0) m.toInt().toString() else m.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryDialog(e: PlanEntry, ref: RefPrompt?, onDismiss: () -> Unit, onPlace: (Int?) -> Unit, onScale: (Double) -> Unit, onRemove: () -> Unit, onOpen: (Any) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.title ?: "Recipe") },
        text = {
            // Scrolls: with large text the dialog can be taller than the screen.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // First: until this page is added, the recipe's shopping list is incomplete.
                ref?.let { r ->
                    Text("It uses page ${r.page} (“${r.raw}”); its ingredients aren't on the shopping list yet.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton({ onOpen(refRoute(r)) }) { Text("Add photo of p.${r.page}") }
                }
                Text("Move to")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { d -> FilterChip(e.day == d, { if (e.day != d) onPlace(d) }, { Text(Weeks.dayLabel(d)) }) }
                    FilterChip(e.day == null, { if (e.day != null) onPlace(null) }, { Text("No night (tray)") })
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
