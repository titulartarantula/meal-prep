package dev.mealprep.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.mealprep.app.AppGraph
import dev.mealprep.app.data.Loaded
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.userMessage
import dev.mealprep.app.graph
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
inline fun <reified VM : ViewModel> graphViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val g = LocalContext.current.graph
    return viewModel(key = key, factory = viewModelFactory { initializer { create(g) } })
}

private val SAVED_AT = DateTimeFormatter.ofPattern("EEE h:mm a")

/** Status text that appears or changes without focus: TalkBack reads it out (WCAG 4.1.3). */
fun Modifier.announced(mode: LiveRegionMode = LiveRegionMode.Polite): Modifier = semantics { liveRegion = mode }

@Composable
fun OfflineBanner(since: Instant?) {
    if (since == null) return
    Text(
        "Offline — showing the copy saved ${since.atZone(ZoneId.systemDefault()).format(SAVED_AT)}",
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(8.dp).announced(),
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** An error or a "do this first" message, read out by TalkBack when it appears. */
@Composable
fun MessageText(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp).announced())
}

/** Advice, not an error ("⚠ buy the cod on Wednesday"): the offline banner's colours, so red stays for what failed. */
@Composable
fun AdviceText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.shapes.small)
        .padding(8.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
}

// Loaded.offline is true for any error that has a saved copy (401 included). The "Offline" banner is only
// honest when the server couldn't be reached, so screens pass these to OfflineBanner / MessageText.
/** For OfflineBanner: non-null only when the server was unreachable / timed out and a saved copy is shown. */
val Loaded<*>.offlineSince: Instant? get() =
    if (offline && (error == ApiError.Unreachable || error == ApiError.TimedOut)) fetchedAt else null

/** For MessageText: the error's message unless it is already explained by the offline banner. */
val Loaded<*>.errorMessage: String? get() = if (offlineSince != null) null else error?.userMessage()
