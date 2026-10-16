package dev.mealprep.app.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.Choice
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The product open in the chooser's detail view: how many to buy (packs, or grams by weight) and the pin. */
data class ChoiceDetail(val choice: Choice, val quantity: Int, val pin: Boolean)

data class ChooserState(
    val line: DraftLine? = null,
    /** The cart can still be changed (ready, not an older cart). */
    val editable: Boolean = false,
    /** The search as typed; null = the line's item name. [shownTerm] = what the server searched. */
    val term: String? = null,
    val shownTerm: String = "",
    val choices: List<Choice> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val filters: Set<ChoiceFilter> = emptySet(),
    val detail: ChoiceDetail? = null,
    val saving: Boolean = false,
    /** The line changed (Use this): the screen goes back to the cart. */
    val done: Boolean = false,
) {
    val shown: List<Choice> get() = ChooserText.filtered(choices, filters)
}

/** Choose a cart line's product: Loblaws' results a page at a time, the detail view, and Use this (one PATCH). */
class ChooserViewModel(private val repo: Repository, private val draftId: Int, private val lineId: Int) : ViewModel() {
    private val _state = MutableStateFlow(ChooserState())
    val state = _state.asStateFlow()
    private var loading: Job? = null

    init { start() }

    fun start() {
        _state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            when (val r = repo.draft(draftId)) {
                is ApiResult.Ok -> {
                    val line = r.value.lines.firstOrNull { it.id == lineId }
                    if (line == null) { _state.update { it.copy(loading = false, error = GONE) }; return@launch }
                    _state.update { it.copy(line = line, editable = r.value.status == "ready" && !r.value.stale) }
                    page(0, _state.value.term)
                }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = r.error.userMessage()) }
            }
        }
    }

    private suspend fun page(n: Int, term: String?) {
        when (val r = repo.choices(draftId, lineId, term, n)) {
            is ApiResult.Ok -> _state.update { s ->
                s.copy(choices = if (n == 0) ChooserText.merge(emptyList(), r.value.choices) else ChooserText.merge(s.choices, r.value.choices),
                    page = n, hasMore = r.value.hasMore, shownTerm = r.value.term, loading = false, loadingMore = false, error = null)
            }
            is ApiResult.Err -> _state.update { it.copy(loading = false, loadingMore = false, error = searchMessage(r.error)) }
        }
    }

    /** A new search (blank = the item name again). */
    fun search(term: String) {
        val t = term.trim().ifEmpty { null }
        _state.update { it.copy(term = t, choices = emptyList(), page = 0, hasMore = false, loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch { page(0, t) }
    }

    fun showMore() {
        val s = _state.value
        if (!s.hasMore || s.loadingMore || s.loading) return
        _state.update { it.copy(loadingMore = true, error = null) }
        loading = viewModelScope.launch { page(s.page + 1, s.term) }
    }

    fun toggle(f: ChoiceFilter) = _state.update { it.copy(filters = if (f in it.filters) it.filters - f else it.filters + f) }

    /** Out-of-stock products can't be opened (nothing to buy this week). */
    fun open(c: Choice) {
        if (c.product.outOfStock) return
        val start = if (c.current) _state.value.line?.quantity?.takeIf { it > 0 } ?: c.suggestedQuantity else c.suggestedQuantity
        _state.update { it.copy(detail = ChoiceDetail(c, ChooserText.start(start, c.product), c.pinned)) }
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    fun more() = _state.update { s -> s.detail?.let { d -> ChooserText.more(d.quantity, d.choice.product)?.let { s.copy(detail = d.copy(quantity = it)) } } ?: s }
    fun fewer() = _state.update { s -> s.detail?.let { d -> ChooserText.fewer(d.quantity, d.choice.product)?.let { s.copy(detail = d.copy(quantity = it)) } } ?: s }
    fun setPin(pin: Boolean) = _state.update { s -> s.detail?.let { s.copy(detail = it.copy(pin = pin)) } ?: s }

    /** Use this: the product (only if it changes), the quantity and the pin (only if it changes) in one PATCH. */
    fun use() {
        val s = _state.value
        val d = s.detail ?: return
        if (!s.editable || s.saving) return
        val line = s.line
        val sameProduct = d.choice.current || line?.product?.code == d.choice.product.code
        if (sameProduct && line?.quantity == d.quantity && d.pin == d.choice.pinned) {
            _state.update { it.copy(done = true) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val r = repo.useProduct(draftId, lineId, if (sameProduct) null else d.choice.product.code, d.quantity,
                d.pin.takeIf { it != d.choice.pinned })
            when (r) {
                is ApiResult.Ok -> _state.update { it.copy(saving = false, line = r.value, done = true) }
                is ApiResult.Err -> _state.update {
                    it.copy(saving = false, error = if ((r.error as? ApiError.Http)?.code == 409) DraftViewModel.CHANGED_HERE else r.error.userMessage(),
                        editable = it.editable && (r.error as? ApiError.Http)?.code != 409)
                }
            }
        }
    }

    private fun searchMessage(e: ApiError): String =
        if (e is ApiError.Http && e.code == 502) "Loblaws didn't answer just now. Try again in a minute." else e.userMessage()

    companion object {
        const val GONE = "This line isn't in the cart any more."
    }
}
