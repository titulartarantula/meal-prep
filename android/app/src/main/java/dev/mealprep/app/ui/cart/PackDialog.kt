package dev.mealprep.app.ui.cart

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mealprep.app.R
import dev.mealprep.app.data.api.DraftLine
import dev.mealprep.app.data.api.PackInfo
import dev.mealprep.app.ui.common.GardenChip
import dev.mealprep.app.ui.common.announced

/** Words for a pack sold by the piece (the server keeps any short word; these are the ones offered). */
val PACK_WORDS = listOf("bunch", "bag", "pack")
const val PACK_SIZE = "Pack size"
const val MAX_PIECES = 100

object PackText {
    /** "One pack: a bunch of about 6 (learned by the cart check)" for the ⋮ menu; null without a pack. */
    fun summary(pack: PackInfo?): String? = pack?.let {
        "One pack: ${article(it.word)} ${it.word} of about ${it.pieces} (" + when (it.source) {
            "user" -> "your number"
            "check" -> "learned by the cart check"
            else -> "a guess"
        } + ")"
    }

    fun question(line: DraftLine, word: String): String = "How many ${line.name} come in one $word?"

    /** Forget is offered for a saved number (learned or the household's), not for the planner's guess. */
    fun canForget(pack: PackInfo?): Boolean = pack != null && pack.source != "guess"

    private fun article(word: String) = if (word.take(1).lowercase() in setOf("a", "e", "i", "o", "u")) "an" else "a"
}

/**
 * Pack size: how many of the line's item one pack holds ("a bunch has 6"). Saved for the product, so every cart plans
 * it that way from now on; Forget goes back to counting piece by piece (or the app's guess).
 */
@Composable
fun PackDialog(line: DraftLine, onSave: (Int, String) -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    var pieces by remember { mutableIntStateOf(line.pack?.pieces ?: 1) }
    var word by remember { mutableStateOf(line.pack?.word?.takeIf { it in PACK_WORDS } ?: PACK_WORDS[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(PACK_SIZE) },
        text = {
            Column {
                Text(PackText.question(line, word), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton({ pieces-- }, Modifier.size(48.dp), enabled = pieces > 1) {
                        Icon(painterResource(R.drawable.ic_remove), contentDescription = "One fewer in a $word")
                    }
                    Text("$pieces", Modifier.padding(horizontal = 20.dp)
                        .semantics { contentDescription = "$pieces in one $word" }.announced(),
                        style = MaterialTheme.typography.headlineSmall)
                    OutlinedIconButton({ pieces++ }, Modifier.size(48.dp), enabled = pieces < MAX_PIECES) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = "One more in a $word")
                    }
                }
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PACK_WORDS.forEach { w -> GardenChip(word == w, { word = w }, w) }
                }
                Text("Saved for this product: every cart counts it this way.", Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ onSave(pieces, word) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (PackText.canForget(line.pack)) TextButton(onForget) { Text("Forget") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}
