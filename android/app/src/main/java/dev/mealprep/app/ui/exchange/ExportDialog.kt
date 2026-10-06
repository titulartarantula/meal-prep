package dev.mealprep.app.ui.exchange

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.core.ExportFiles
import dev.mealprep.app.ui.common.MessageText
import dev.mealprep.app.ui.common.announced
import dev.mealprep.app.ui.common.graphViewModel
import java.io.File

const val EXPORT_ALL = "Export all recipes"
const val SHARE_ONE = "Share or save this recipe"
const val SAVE_TO_FILE = "Save to a file"
const val SEND = "Send…"
const val GETTING_READY = "Getting your recipes ready…"
const val EXPORT_ALL_TEXT = "One file with every recipe, with its ratings and notes: a backup to keep, or to import into " +
    "Meal Prep on another phone. Other recipe apps can read it too."
const val SHARE_ONE_TEXT = "A file with this recipe, its ratings and notes. Meal Prep and other recipe apps can import it."

/**
 * Export, from the Recipes tab's More options ([recipeId] null: the whole library) or a recipe's Share button. Opens
 * the system's document picker for Save to a file, or the share sheet for Send….
 */
@Composable
fun ExportHost(recipeId: Int?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val vm = graphViewModel(key = "export-${recipeId ?: "all"}") { g ->
        ExportViewModel(g.repo, g.exportsDir, recipeId, write = { f, u -> ExportFiles.copyTo(g.context.contentResolver, f, u) })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reset() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFiles.MIME)) { uri -> vm.saveTo(uri) }
    LaunchedEffect(state.pending) {
        val file = state.file ?: return@LaunchedEffect
        when (state.pending) {
            ExportAction.SAVE -> runCatching { save.launch(file.name) }
            ExportAction.SEND -> {
                runCatching { ctx.startActivity(sendIntent(ctx, file.file, file.name, recipeId == null)) }
                vm.handled(); onClose(); return@LaunchedEffect     // the share sheet is the feedback
            }
            null -> return@LaunchedEffect
        }
        vm.handled()
    }
    ExportDialog(all = recipeId == null, state = state, onSave = { vm.start(ExportAction.SAVE) },
        onSend = { vm.start(ExportAction.SEND) }, onRetry = vm::retry, onDismiss = onClose)
}

fun sendIntent(ctx: Context, file: File, name: String, all: Boolean) = ExportFiles.sendIntent(
    FileProvider.getUriForFile(ctx, ctx.packageName + ExportFiles.AUTHORITY_SUFFIX, file), name,
    if (all) "Send recipes" else "Send this recipe")

@Composable
fun ExportDialog(all: Boolean, state: ExportState, onSave: () -> Unit, onSend: () -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (all) EXPORT_ALL else SHARE_ONE) },
        text = {
            // Scrolls at 200 % text; every state is said in words (and read out as it changes).
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (all) EXPORT_ALL_TEXT else SHARE_ONE_TEXT, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onSave, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(SAVE_TO_FILE) }
                OutlinedButton(onSend, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(SEND) }
                if (state.busy) {
                    Text(GETTING_READY, Modifier.announced())
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.saved?.let { Text(it, Modifier.announced(), color = MaterialTheme.colorScheme.primary) }
                state.error?.let {
                    MessageText(it)
                    TextButton(onRetry, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(if (state.saved != null) "Done" else "Close") } },
    )
}
