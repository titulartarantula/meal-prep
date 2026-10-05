package dev.mealprep.app.ui.camera

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One recipe's pages while they are being photographed: snap → Add page / Done, with retake, reorder and delete.
 * [scale] shrinks a raw capture into a page file; [copy] does the same for a photo picked from the gallery.
 */
class CameraViewModel(
    private val store: PageStore,
    private val scale: suspend (File, File) -> Unit,
    private val copy: suspend (Uri, File) -> Unit,
) : ViewModel() {
    companion object {
        const val SAVE_FAILED = "Couldn't save that photo. Try again."
        const val PICK_FAILED = "Couldn't open one of those photos."
        const val CAMERA_FAILED = "The camera didn't take the photo. Try again."
        const val ONLY_TEN = "A recipe can have up to ${PagesState.MAX_PAGES} pages — the extra photos were left out."
    }

    val dir: File = store.newBatch()
    private val _state = MutableStateFlow(PagesState())
    val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var finished = false

    /** Where the next capture is written (inside this recipe's folder, so it is cleaned up with it). */
    fun rawFile(): File = File(dir, "raw-${UUID.randomUUID()}.jpg")

    fun onCaptured(raw: File) {
        _busy.value = true
        viewModelScope.launch {
            val out = store.newFile(dir)
            try {
                scale(raw, out)
                _state.update { it.add(out) }
                _error.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                out.delete()
                _error.value = SAVE_FAILED
            } finally {
                raw.delete()
                _busy.value = false
            }
        }
    }

    /** The camera reported an error instead of a photo. */
    fun onCaptureFailed(raw: File) { raw.delete(); _error.value = CAMERA_FAILED }

    /** Photos chosen from the gallery are added after the current pages, in the order they were picked. */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _busy.value = true
        viewModelScope.launch {
            var failed = 0
            var skipped = 0
            for (u in uris) {
                if (!_state.value.canShoot) { skipped++; continue }
                val out = store.newFile(dir)
                try {
                    copy(u, out)
                    _state.update { it.add(out) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    out.delete(); failed++
                }
            }
            _error.value = when { failed > 0 -> PICK_FAILED; skipped > 0 -> ONLY_TEN; else -> null }
            _busy.value = false
        }
    }

    fun retake(i: Int) = _state.update { it.retake(i) }
    fun cancelRetake() = _state.update { it.cancelRetake() }
    fun remove(i: Int) = _state.update { it.remove(i) }
    fun move(i: Int, by: Int) = _state.update { it.move(i, by) }

    /** Numbers the pages in the chosen order; the folder then belongs to the import (or the share screen). */
    fun done(): File {
        finished = true
        store.commitOrder(dir, _state.value.pages)
        return dir
    }

    override fun onCleared() { if (!finished) store.discard(dir) }   // backed out without Done
}
