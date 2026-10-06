package dev.mealprep.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mealprep.app.R

const val NAVIGATE_UP = "Navigate up"

/**
 * The one top bar of every screen that isn't a tab: Back ("Navigate up"), the title as a heading, an optional
 * subtitle (the date or week) and actions. Laid out like Material's small top app bar, but the title may wrap to a
 * second line (large text cut long recipe titles off) and the bar grows with it.
 */
@Composable
fun BackTopBar(
    title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = NAVIGATE_UP) }
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        actions()
    }
}

/**
 * The screen's main action, docked at the bottom (in thumb reach) across the whole width: Start Sunday prep, Add to
 * a week, Build cart, Send to Loblaws, Add a staple. [above] goes over the button (the cart's total).
 */
@Composable
fun BottomActionBar(modifier: Modifier = Modifier, above: @Composable ColumnScope.() -> Unit = {}, button: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            above()
            button()
        }
    }
}

/** [BottomActionBar] with one full-width filled button. */
@Composable
fun BottomAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    BottomActionBar(modifier) {
        Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
    }
