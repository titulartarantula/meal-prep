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
    /** A document being read by the server's AI (its read job: progress), while [loading]. */
    val reading: ImportReport? = null,
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
 * Import recipes from a file: the preview (instant: what's new, already in Recipes, changed, unreadable; for a recipe
 * document read the first time, the server's read job is polled until its report is the preview), the ticks,
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
        _state.update { it.copy(loading = true, error = null, reading = null) }
        polling?.cancel()
        polling = viewModelScope.launch {
            when (val r = repo.importPreview(f, _state.value.name)) {
                is ApiResult.Ok -> if (ImportLogic.isRead(r.value) && r.value.status == "running") read(r.value) else show(r.value)
                // Try again only helps when the file wasn't the problem (offline, slow, the token, the server).
                is ApiResult.Err -> _state.update { it.copy(loading = false, error = ExchangeText.importError(r.error),
                    canRetry = r.error !is ApiError.Http || r.error.code >= 500) }
            }
        }
    }

    /** A document: the server's AI finds its recipes (a read job); poll it until it is the preview. A Wi-Fi drop keeps
     *  polling; a failed read or a job the server doesn't know any more ends with the reason and Try again. */
    private suspend fun read(job: ImportReport) {
        var current = job
        while (true) {
            _state.update { it.copy(reading = current) }
            if (current.status == "done") return show(current)
            if (current.status == "failed") {
                _state.update { it.copy(loading = false, reading = null, error = ExchangeText.readError(current.error), canRetry = true) }
                return
            }
            delay(pollMs)
            val l = repo.importJob(current.id ?: return)
            val next = l.value
            val err = l.error
            if (next != null && next.id == current.id) current = next
            else if (err is ApiError.Http) {
                _state.update { it.copy(loading = false, reading = null, error = ExchangeText.importError(err), canRetry = true) }
                return
            }
        }
    }

    private fun show(report: ImportReport) {
        val keep = saved.get<ArrayList<String>>(TICKS)?.toSet()
        val ticks = keep?.intersect(ImportLogic.selectableKeys(report)) ?: ImportLogic.defaultTicks(report)
        _state.update { it.copy(loading = false, reading = null, report = report, ticks = ticks, applyError = null) }
    }

    fun toggle(key: String) {
        val s = _state.value
        val item = s.report?.items?.firstOrNull { it.key == key } ?: return
        if (!ImportLogic.selectable(item) || s.job != null || s.starting) return
        val ticks = if (key in s.ticks) s.ticks - key else s.ticks + key
        saved[TICKS] = ArrayList(ticks)
        _state.update { it.copy(ticks = ticks) }
    }

    /** Select all / Select none (a document with many recipes). */
    fun selectAll(all: Boolean) {
        val s = _state.value
        val report = s.report ?: return
        if (s.job != null || s.starting) return
        val ticks = if (all) ImportLogic.selectableKeys(report) else emptySet()
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
            when (val r = repo.importApply(f, ImportLogic.choices(report, s.ticks), s.name)) {
                is ApiResult.Ok -> {
                    val id = r.value.id
                    if (id == null) { _state.update { it.copy(starting = false, applyError = ExchangeText.COULDNT_READ) }; return@launch }
                    saved[JOB] = id
                    watch(id)
                    f.delete()   // the job has what it needs; the copy is never read again
                    _state.update { it.copy(starting = false, job = r.value) }
                    if (ImportLogic.running(r.value)) poll(id)
                }
                is ApiResult.Err -> {
                    val message = ExchangeText.importError(r.error, apply = true)
                    _state.update { it.copy(starting = false, applyError = message) }
                    // The server no longer has this document's read (it keeps them two weeks): read it again.
                    if (message == ExchangeText.READ_AGAIN) { _state.update { it.copy(report = null) }; preview() }
                }
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
