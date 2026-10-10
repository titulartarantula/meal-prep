package dev.mealprep.app.ui.library

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.mealprep.app.R
import dev.mealprep.app.core.ShareParser

/** The ways to add a recipe from the Recipes tab (a file: a recipe document, a recipe or a whole library exported from
 *  another app, or photos of a recipe's pages from Files or a cloud drive). */
enum class AddWay(val label: String) {
    CAMERA("Scan with camera"), PHOTOS("Choose photos"), LINK("Paste an NYT link"), FILE("Import from a file"),
}

/** "Add recipe" (bottom right, in thumb reach) and its menu. */
@Composable
fun AddRecipeButton(onPick: (AddWay) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        // The content overload: the icon + text one animates its label, which hides it from tests and TalkBack
        // until the animation has run.
        ExtendedFloatingActionButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add recipe")
        }
        DropdownMenu(open, { open = false }) {
            AddWay.entries.forEach { w -> DropdownMenuItem({ Text(w.label) }, { open = false; onPick(w) }) }
        }
    }
}

/** The NYT Cooking link on the clipboard, if it holds one (read only when the paste dialog opens). */
fun clipboardNytLink(ctx: Context): String? = runCatching {
    val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    (0 until clip.itemCount).firstNotNullOfOrNull { i -> ShareParser.nytUrl(clip.getItemAt(i).coerceToText(ctx)?.toString()) }
}.getOrNull()

const val NOT_NYT_LINK = "That isn't an NYT Cooking recipe link (cooking.nytimes.com/recipes/…)."

/** Paste or type an NYT Cooking link; [copied] (from the clipboard) is offered with one tap. Save → [onSave] (url). */
@Composable
fun PasteLinkDialog(copied: String?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val url = ShareParser.nytUrl(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste an NYT link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                copied?.let { c ->
                    Text("You copied a recipe link:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton({ text = c }) { Text("Use ${c.removePrefix("https://")}", maxLines = 2) }
                }
                OutlinedTextField(text, { text = it }, label = { Text("NYT Cooking link") }, singleLine = true,
                    isError = text.isNotBlank() && url == null,
                    supportingText = { if (text.isNotBlank() && url == null) Text(NOT_NYT_LINK) })
            }
        },
        confirmButton = { TextButton({ url?.let(onSave) }, enabled = url != null) { Text("Save to Recipes") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
