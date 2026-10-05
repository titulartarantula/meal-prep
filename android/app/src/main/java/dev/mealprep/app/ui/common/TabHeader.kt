package dev.mealprep.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mealprep.app.R

/** The ⋮ menu on the main screens (Snap a cookbook recipe, Staples, Settings): label → route. */
@Composable
fun OverflowMenu(items: List<Pair<String, Any>>, onOpen: (Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }) { Icon(painterResource(R.drawable.ic_more_vert), contentDescription = "More options") }
        DropdownMenu(open, { open = false }) {
            items.forEach { (label, route) -> DropdownMenuItem({ Text(label) }, { open = false; onOpen(route) }) }
        }
    }
}

/** Title row of a main (bottom-bar) screen. */
@Composable
fun TabHeader(title: String, menu: List<Pair<String, Any>>, onOpen: (Any) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        OverflowMenu(menu, onOpen)
    }
}
