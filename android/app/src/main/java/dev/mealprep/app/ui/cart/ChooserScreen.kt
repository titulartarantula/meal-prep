package dev.mealprep.app.ui.cart

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.R
import dev.mealprep.app.data.api.Choice
import dev.mealprep.app.data.api.Product
import dev.mealprep.app.ui.common.BackTopBar
import dev.mealprep.app.ui.common.BottomActionBar
import dev.mealprep.app.ui.common.GardenChip
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.ProductImage
import dev.mealprep.app.ui.common.announced
import dev.mealprep.app.ui.common.graphViewModel
import dev.mealprep.app.ui.theme.GardenAccent
import kotlinx.coroutines.launch

/** Callbacks of the chooser (one object so the stateless content stays readable). */
class ChooserActions(
    val onBack: () -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onMore: () -> Unit = {},
    val onFilter: (ChoiceFilter) -> Unit = {},
    val onOpen: (Choice) -> Unit = {},
    val onCloseDetail: () -> Unit = {},
    val onFewer: () -> Unit = {},
    val onMoreQty: () -> Unit = {},
    val onPin: (Boolean) -> Unit = {},
    val onUse: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

const val SHOW_MORE = "Show more"
const val USE_THIS = "Use this"
const val ALWAYS_BUY = "Always buy this"
const val OUT_THIS_WEEK = "Out of stock this week"

/**
 * Choose a cart line's product, full screen: Loblaws' results for the item (or a search) a page at a time, with the
 * current pick first, then what was bought before, deals, the rest, and out-of-stock products last (greyed, not
 * selectable). Tapping a product opens its detail: photos, description, prices, how many, Always buy this, Use this.
 */
@Composable
fun ChooserContent(s: ChooserState, a: ChooserActions) {
    val d = s.detail
    if (d != null) ChoiceDetailContent(s, d, a) else ChoiceList(s, a)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceList(s: ChooserState, a: ChooserActions) {
    val line = s.line
    Column(Modifier.fillMaxSize()) {
        BackTopBar(if (line?.product == null) "Find ${line?.name ?: "a product"}" else "Choose ${line.name}", a.onBack,
            subtitle = line?.let { needText(it) })
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            var term by remember { mutableStateOf(s.term ?: "") }
            val canSearch = !s.loading
            OutlinedTextField(term, { term = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(SEARCH_LOBLAWS) }, placeholder = { Text(line?.name ?: "") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (canSearch) a.onSearch(term) }),
                trailingIcon = {
                    IconButton({ a.onSearch(term) }, enabled = canSearch) {
                        Icon(painterResource(R.drawable.ic_search), contentDescription = "Search")
                    }
                })
            FlowRow(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceFilter.entries.forEach { f ->
                    GardenChip(f in s.filters, { a.onFilter(f) }, f.label, Modifier.heightIn(min = 48.dp), toggle = true)
                }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            MessageText(s.error)
            if (s.error != null && s.choices.isEmpty() && !s.loading) OutlinedButton(a.onRetry, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
            if (!s.loading && s.error == null) Text(countText(s), Modifier.padding(vertical = 4.dp).announced(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f)) {
                items(s.shown, key = { it.product.code }) { c -> ChoiceCard(c) { a.onOpen(c) } }
                if (s.hasMore || s.loadingMore) item(key = "more") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (s.loadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else OutlinedButton(a.onMore, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(SHOW_MORE) }
                    }
                }
            }
        }
    }
}

/** "26 products for “cheddar”", or how many of them the filters keep. */
internal fun countText(s: ChooserState): String {
    val n = s.shown.size
    val what = "$n ${if (n == 1) "product" else "products"}"
    return when {
        s.choices.isEmpty() -> "Nothing found for “${s.shownTerm}” — try other words."
        s.filters.isNotEmpty() && n == 0 -> "None of these match the filters" + if (s.hasMore) " — Show more to look further." else "."
        s.filters.isNotEmpty() -> "$what match the filters"
        else -> "$what for “${s.shownTerm}”"
    }
}

/** A small label on a coloured tile ("SAVE $0.99 · ends Oct 21"), or outlined ("Ad"). */
@Composable
private fun Tag(text: String, container: Color, content: Color, modifier: Modifier = Modifier, border: BorderStroke? = null) {
    Surface(modifier, color = container, contentColor = content, shape = MaterialTheme.shapes.small, border = border) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Labels(c: Choice) {
    val p = c.product
    val cs = MaterialTheme.colorScheme
    val accent = GardenAccent.current
    val tags = listOfNotNull(
        "current".takeIf { c.current }, "pinned".takeIf { c.pinned }, "ad".takeIf { p.sponsored },
        ChooserText.deal(p)?.let { "deal" to it }, ChooserText.optimum(p)?.let { "opt" to it })
    if (tags.isEmpty()) return
    FlowRow(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (c.current) Tag("Current pick", cs.primary, cs.onPrimary)
        if (c.pinned) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_pin), contentDescription = null, Modifier.size(16.dp), tint = cs.primary)
            Text(ALWAYS_BOUGHT, style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        // Outlined: a paid placement must stand out from the card, not only by a tint close to it.
        if (p.sponsored) Tag("Ad", cs.surface, cs.onSurface, Modifier.semantics { contentDescription = "Ad, sponsored by the brand" },
            border = BorderStroke(1.dp, cs.outline))
        ChooserText.deal(p)?.let { Tag(it, cs.tertiaryContainer, cs.onTertiaryContainer) }
        ChooserText.optimum(p)?.let { Tag(it, accent.container, accent.onContainer) }
    }
}

const val ALWAYS_BOUGHT = "Always bought"

@Composable
private fun ChoiceCard(c: Choice, onOpen: () -> Unit) {
    val p = c.product
    val out = p.outOfStock
    val cs = MaterialTheme.colorScheme
    val click = if (out) Modifier else Modifier.clickable(role = Role.Button, onClickLabel = "See details", onClick = onOpen)
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).then(click)) {
        Row(Modifier.padding(12.dp)) {
            ProductImage(p.image, null, Modifier.size(72.dp), dim = out)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Labels(c)
                Text(listOfNotNull(p.brand, p.name).joinToString(" · "), style = MaterialTheme.typography.bodyLarge,
                    color = if (out) cs.onSurfaceVariant else cs.onSurface)
                p.packageSize?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (out) Text(OUT_THIS_WEEK, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cs.error)
                else ChooserText.priceLine(p).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                ChooserText.lastTime(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                if (!out) ChooserText.betterValue(c)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
            }
        }
    }
}

/** The detail of one product: photos (swipe, or ‹ ›), prices and offers, description, how many, the pin, Use this. */
@Composable
private fun ChoiceDetailContent(s: ChooserState, d: ChoiceDetail, a: ChooserActions) {
    val c = d.choice
    val p = c.product
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        BackTopBar(listOfNotNull(p.brand, p.name).joinToString(" · "), a.onCloseDetail, subtitle = p.packageSize?.ifBlank { null })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Photos(p)
            Labels(c)
            ChooserText.priceLine(p).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            ChooserText.lastTime(c)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
            ChooserText.betterValue(c)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.primary) }
            if (s.editable) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)
                .toggleable(d.pin, role = Role.Checkbox, onValueChange = a.onPin), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(d.pin, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(ALWAYS_BUY, style = MaterialTheme.typography.bodyLarge)
                    Text("Every week for ${s.line?.name ?: "this item"}, even when it isn't among the first results",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            p.description?.let {
                Text("About this product", Modifier.padding(top = 12.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                Text(it, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        MessageText(s.error)
        BottomActionBar(above = { if (s.editable) Stepper(s, d, a) }) {
            Button(a.onUse, enabled = s.editable && !s.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (s.saving) "Saving…" else if (s.editable) USE_THIS else "This cart can't be changed")
            }
        }
    }
}

@Composable
private fun Stepper(s: ChooserState, d: ChoiceDetail, a: ChooserActions) {
    val p = d.choice.product
    val name = s.line?.name ?: p.name
    val q = d.quantity
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (p.weighted) "How much" else "How many", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        OutlinedIconButton(a.onFewer, Modifier.size(48.dp), enabled = ChooserText.fewer(q, p) != null) {
            Icon(painterResource(R.drawable.ic_remove), contentDescription = ChooserText.fewerLabel(p, name))
        }
        val shown = ChooserText.quantity(q, p)
        Text(shown, Modifier.padding(horizontal = 12.dp).semantics { contentDescription = "Quantity $shown" }.announced(),
            style = MaterialTheme.typography.titleMedium)
        OutlinedIconButton(a.onMoreQty, Modifier.size(48.dp), enabled = ChooserText.more(q, p) != null) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = ChooserText.moreLabel(p, name))
        }
    }
    p.cost(q)?.let { Text("About ${price(it)}", Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Photos(p: Product) {
    val urls = p.images.ifEmpty { listOfNotNull(p.image) }
    if (urls.size <= 1) {
        ProductImage(urls.firstOrNull(), p.name, Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp))
        return
    }
    val pager = rememberPagerState { urls.size }
    val scope = rememberCoroutineScope()
    HorizontalPager(pager, Modifier.fillMaxWidth().height(260.dp).padding(vertical = 8.dp)) { i ->
        ProductImage(urls[i], "${p.name}, photo ${i + 1} of ${urls.size}", Modifier.fillMaxSize())
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        IconButton({ scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, enabled = pager.currentPage > 0) {
            Icon(painterResource(R.drawable.ic_chevron_left), contentDescription = "Previous photo")
        }
        Text("Photo ${pager.currentPage + 1} of ${urls.size}", Modifier.announced(), style = MaterialTheme.typography.bodySmall)
        IconButton({ scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, enabled = pager.currentPage < urls.size - 1) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = "Next photo")
        }
    }
}

@Composable
fun ChooserScreen(draftId: Int, lineId: Int, onDone: () -> Unit, onBack: () -> Unit) {
    val vm = graphViewModel(key = "chooser-$draftId-$lineId") { g -> ChooserViewModel(g.repo, draftId, lineId) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onDone() }
    BackHandler(enabled = s.detail != null) { vm.closeDetail() }
    ChooserContent(s, ChooserActions(
        onBack = onBack, onSearch = vm::search, onMore = vm::showMore, onFilter = vm::toggle, onOpen = vm::open,
        onCloseDetail = vm::closeDetail, onFewer = vm::fewer, onMoreQty = vm::more, onPin = vm::setPin, onUse = vm::use,
        onRetry = vm::start,
    ))
}
