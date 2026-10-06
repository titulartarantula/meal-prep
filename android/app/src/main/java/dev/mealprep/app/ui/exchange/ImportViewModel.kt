package dev.mealprep.app.ui.exchange

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.data.Repository
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.ImportReport
import dev.mealprep.app.ui.common.offlineSince
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportState(
    /** The file's name where it came from (the screen's subtitle). */
    val name: String = "",
    /** Reading the file (the preview). */
    val loading: Boolean = false,
    /** The whole file couldn't be read, or isn't here ([canRetry]: Try again makes sense). */
    val error: String? = null,
    val canRetry: Boolean = false,
    val report: ImportReport? = null,
    /** Ticked item keys. */
    val ticks: Set<String> = emptySet(),
    /** Sending the choices (Add …). */
    val starting: Boolean = false,
    val applyError: String? = null,
    /** The import job, once started: progress, then the result. */
    val job: ImportReport? = null,
    val jobError: String? = null,
    val offlineSince: Instant? = null,
) {
    val adds: Int get() = report?.let { ImportLogic.adds(it, ticks) } ?: 0
    val updates: Int get() = report?.let { ImportLogic.updates(it, ticks) } ?: 0
    val canApply: Boolean get() = report != null && job == null && !starting && adds + updates > 0
    val buttonLabel: String get() = ImportLogic.buttonLabel(adds, updates)
}

/**
 * Import recipes from a file: the preview (instant: what's new, already in Recipes, changed, unreadable), the ticks,
 * then Add → the server's background job, polled here while the screen is open (JobWatchWorker carries on and
 * notifies if the app is closed). [jobId] > 0 opens a job already started (a notification, or after process death).
 * The ticks and the job id survive process death in [saved]; the report is simply read again.
 */
class ImportViewModel(
    private val repo: Repository,
    private var file: File?,
    name: String,
    initialError: String?,
    jobId: Int,
    private val saved: SavedStateHandle,
    /** Keep watching the job in the background (JobWatcher.watchImport). */
    private val watch: (Int) -> Unit = {},
    private val pollMs: Long = 2_000,
) : ViewModel() {
    companion object {
        const val TICKS = "ticks"; const val JOB = "job"
        const val FILE_GONE = "That file isn't here any more. Choose it again."
    }

    private val _state = MutableStateFlow(ImportState(name = name))
    val state = _state.asStateFlow()
    private var polling: Job? = null

    init {
        val job = saved.get<Int>(JOB) ?: jobId
        when {
            job > 0 -> { saved[JOB] = job; poll(job) }
            initialError != null -> _state.update { it.copy(error = initialError) }
            else -> preview()
        }
    }

    /** Another file chosen from this screen (after an error). */
    fun newFile(f: File, name: String) {
        if (_state.value.job != null || _state.value.starting) return
        file = f
        saved[TICKS] = null
        _state.value = ImportState(name = name)
        preview()
    }

    /** A file chosen from this screen couldn't be copied in (too big, unreadable). */
    fun fileError(message: String) {
        if (_state.value.job != null || _state.value.starting) return
        _state.update { it.copy(error = message, canRetry = false, loading = false, report = null) }
    }

    fun preview() {
        val f = file?.takeIf { it.exists() } ?: run { _state.update { it.copy(error = FILE_GONE, canRetry = false, loading = false) }; return }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val r = repo.importPreview(f)) {
                is ApiResult.Ok -> {
                    val keep = saved.get<ArrayList<String>>(TICKS)?.toSet()
                    val selectable = r.value.items.filter(ImportLogic::selectable).map { it.key }.toSet()
                    val ticks = keep?.intersect(selectable) ?: ImportLogic.defaultTicks(r.value)
                    _state.update { it.copy(loading = false, report = r.value, ticks = ticks) }
                }
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = ExchangeText.importError(r.error), canRetry = true) }
            }
        }
    }

    fun toggle(key: String) {
        val s = _state.value
        val item = s.report?.items?.firstOrNull { it.key == key } ?: return
        if (!ImportLogic.selectable(item) || s.job != null || s.starting) return
        val ticks = if (key in s.ticks) s.ticks - key else s.ticks + key
        saved[TICKS] = ArrayList(ticks)
        _state.update { it.copy(ticks = ticks) }
    }

    /** Add …: sends the choices once; a retry after a lost answer returns the same job (the server's send-once). */
    fun apply() {
        val s = _state.value
        val report = s.report ?: return
        if (!s.canApply) return
        val f = file?.takeIf { it.exists() } ?: run { _state.update { it.copy(applyError = FILE_GONE) }; return }
        _state.update { it.copy(starting = true, applyError = null) }
        viewModelScope.launch {
            when (val r = repo.importApply(f, ImportLogic.choices(report, s.ticks))) {
                is ApiResult.Ok -> {
                    val id = r.value.id
                    if (id == null) { _state.update { it.copy(starting = false, applyError = ExchangeText.COULDNT_READ) }; return@launch }
                    saved[JOB] = id
                    watch(id)
                    f.delete()   // the job has what it needs; the copy is never read again
                    _state.update { it.copy(starting = false, job = r.value) }
                    if (ImportLogic.running(r.value)) poll(id)
                }
                is ApiResult.Err -> _state.update { it.copy(starting = false, applyError = ExchangeText.importError(r.error, apply = true)) }
            }
        }
    }

    private fun poll(id: Int) {
        polling?.cancel()
        polling = viewModelScope.launch {
            while (true) {
                val l = repo.importJob(id)
                val job = l.value
                _state.update {
                    it.copy(job = job ?: it.job, offlineSince = l.offlineSince,
                        jobError = if (job == null) l.error?.let { e -> ExchangeText.importError(e, job = true) } else null)
                }
                if (job != null && !ImportLogic.running(job)) return@launch           // done (or its saved copy says so)
                if (job == null && l.error != ApiError.Unreachable && l.error != ApiError.TimedOut) return@launch   // 404, token
                delay(pollMs)                                                         // running, or a Wi-Fi drop
            }
        }
    }

    /** Try again after a polling error. */
    fun refresh() { saved.get<Int>(JOB)?.let(::poll) }
}
