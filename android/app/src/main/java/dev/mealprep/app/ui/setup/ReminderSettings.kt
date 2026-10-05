package dev.mealprep.app.ui.setup

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.work.StaplesReminder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

/** Sun … Sat, the app's week order. */
internal val WEEK_DAYS = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

internal fun reminderSummary(n: NotifPrefs): String =
    "Every ${Weeks.longDayLabel(n.staplesDay.value % 7)} at ${n.staplesAt.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))}"

/** Settings section: the optional weekly staples reminder (per phone). */
@Composable
fun StaplesReminderSettings(graph: AppGraph) {
    val s by graph.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    StaplesReminderContent(s.notif) { n ->
        scope.launch {
            graph.settingsStore.update { it.copy(notif = n) }
            StaplesReminder.apply(graph.workManager, n)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StaplesReminderContent(n: NotifPrefs, onChange: (NotifPrefs) -> Unit) {
    val ctx = LocalContext.current
    Column {
        Text("Reminders", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(n.staples, role = Role.Switch) { onChange(n.copy(staples = it)) },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weekly staples reminder")
                Text(if (n.staples) reminderSummary(n) else "Off. A notification to check the staples before you shop (this phone only).",
                    style = MaterialTheme.typography.bodySmall)
            }
            Switch(n.staples, onCheckedChange = null)
        }
        if (n.staples) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEK_DAYS.forEach { d ->
                    FilterChip(n.staplesDay == d, { onChange(n.copy(staplesDay = d)) }, { Text(Weeks.dayLabel(d.value % 7)) })
                }
            }
            TextButton({
                TimePickerDialog(ctx, { _, h, m -> onChange(n.copy(staplesAt = LocalTime.of(h, m))) },
                    n.staplesAt.hour, n.staplesAt.minute, DateFormat.is24HourFormat(ctx)).show()
            }) { Text("Change the time") }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                Text("Notifications are off for Meal Prep, so the reminder can't show. Allow them in Android Settings → Apps → Meal Prep.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
