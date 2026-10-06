package dev.mealprep.app.ui.home

import androidx.work.WorkInfo
import dev.mealprep.app.data.Repository
import dev.mealprep.app.work.ImportQueue
import dev.mealprep.app.work.ImportWorker
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The import queue's jobs as the cards need them. */
fun importJobs(queue: ImportQueue): Flow<List<ImportJob>> = queue.recent.map { infos ->
    infos.map { ImportJob(it.id, it.state, it.outputData, it.runAttemptCount, ImportQueue.kindOf(it.tags)) }
}

/**
 * Background imports as cards. This week and Recipes both show them; [hidden] is shared (AppGraph), so a card
 * dismissed on one tab is gone on the other too. [onDone] runs once per finished job (refresh what it changed).
 */
class ImportFeed(
    private val repo: Repository,
    private val queue: ImportQueue?,
    private val scope: CoroutineScope,
    jobs: Flow<List<ImportJob>>,
    private val hidden: MutableStateFlow<Set<UUID>>,
    private val onDone: (ImportJob) -> Unit = {},
) {
    private val seenDone = mutableSetOf<UUID>()
    private var lastJobs: List<ImportJob> = emptyList()

    val cards: StateFlow<List<ImportUi>> =
        combine(jobs.onEach(::onJobs), hidden, ::importUi).stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun dismiss(id: UUID) {
        hidden.update { it + id }
        scope.launch { repo.markOnce("hide-import:$id") }
    }

    /** Cancel on an import that is waiting for the home network. */
    fun cancel(id: UUID) { queue?.cancel(id) }

    fun retry(id: UUID) {
        val job = lastJobs.firstOrNull { it.id == id } ?: return
        if (!job.output.getBoolean(ImportWorker.RETRY_SAFE, false)) return   // may already be on the server
        queue?.retry(job.output) ?: return
        dismiss(id)
    }

    private suspend fun onJobs(jobs: List<ImportJob>) {
        lastJobs = jobs
        val gone = jobs.filter { it.state.isFinished && it.id !in hidden.value && repo.isMarked("hide-import:${it.id}") }.map { it.id }
        if (gone.isNotEmpty()) hidden.update { it + gone }
        jobs.filter { it.state == WorkInfo.State.SUCCEEDED && seenDone.add(it.id) }.forEach(onDone)
    }
}
