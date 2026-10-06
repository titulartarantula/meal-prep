package dev.mealprep.app.ui.setup

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mealprep.app.AppGraph
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.settings.NotifPrefs
import dev.mealprep.app.notify.Notifier
import dev.mealprep.app.notify.PlannedReminder
import dev.mealprep.app.notify.ReminderKind
import dev.mealprep.app.ui.nav.Nav
import dev.mealprep.app.ui.common.GardenChip
import dev.mealprep.app.work.StaplesReminder
import dev.mealprep.app.work.SyncWorker
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

/** Sun … Sat, the app's week order. */
internal val WEEK_DAYS = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }

private val SHORT_TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

internal fun time(t: LocalTime): String = t.format(SHORT_TIME)

internal fun reminderSummary(n: NotifPrefs): String = "Every ${Weeks.longDayLabel(n.staplesDay.value % 7)} at ${time(n.staplesAt)}"

/** "Wed Oct 14, 9:00 AM: How was Tuesday's Chili?" */
internal fun upcomingLine(r: PlannedReminder): String =
    runCatching { LocalDateTime.parse(r.at).let { "${it.format(DAY_DATE)}, ${time(it.toLocalTime())}: ${r.title}" } }.getOrDefault(r.title)

/** A notification posted now so its look (and where a tap goes) can be checked without waiting for it. */
data class Sample(val tag: String, val channel: String, val title: String, val text: String, val nav: String?)

/** One sample of each kind: the next real reminder of that kind when one is planned (tapping it opens that
 *  dinner), else a made-up one; the cart one opens the shopping list. */
internal fun samples(planned: List<PlannedReminder>, n: NotifPrefs): List<Sample> {
    fun next(k: ReminderKind) = planned.firstOrNull { it.kind == k.name }
    fun sample(k: ReminderKind, title: String, text: String, nav: String?) = next(k)?.let {
        Sample("sample-${k.name.lowercase()}", Notifier.CH_REMINDERS, it.title, "Sample. ${it.text}", it.nav)
    } ?: Sample("sample-${k.name.lowercase()}", Notifier.CH_REMINDERS, title, "Sample. $text", nav)
    return listOf(
        sample(ReminderKind.THAW, "Thaw for tomorrow's dinner",
            "When Sunday's prep froze a kit, this comes at ${time(n.thawAt)} the evening before, saying what to move to the fridge.", Nav.home(null)),
        sample(ReminderKind.RATE, "How was last night's dinner?",
            "This comes at ${time(n.rateAt)} the morning after each dinner on the plan; tap it to rate.", Nav.home(null)),
        sample(ReminderKind.TONIGHT, "Tonight: dinner",
            "This comes at ${time(n.tonightAt)} on the day, opening the cook card" + if (n.tonight) "." else " (it's off on this phone).",
            Nav.home(null)),
        Sample("sample-cart", Notifier.CH_JOBS, "Cart ready to review",
            "Sample. This comes when a cart built on either phone is ready: check the picks, then Send to Loblaws.", Nav.list()),
    )
}

/** Settings section: this phone's notifications (thaw, how was dinner, tonight, cart ready, weekly staples). */
@Composable
fun NotifSettings(graph: AppGraph) {
    val s by graph.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {   // back from Android's settings: allowed now?
        granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    // Re-read the planned list each time a sync finishes (a change here queues one).
    val syncs by remember { graph.workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.NOW) }.collectAsStateWithLifecycle(emptyList())
    val upcoming by produceState(emptyList<PlannedReminder>(), syncs.map { it.state }) {
        val now = LocalDateTime.now()
        value = graph.repo.getLocal(SyncWorker.PLANNED, SyncWorker.plannedSerializer).orEmpty()
            .filter { runCatching { LocalDateTime.parse(it.at).isAfter(now) }.getOrDefault(false) }
    }
    NotifSettingsContent(s.notif, granted, upcoming,
        onChange = { n ->
            scope.launch {
                graph.settingsStore.update { it.copy(notif = n) }
                StaplesReminder.apply(graph.workManager, n)
                SyncWorker.now(graph.workManager)
            }
        },
        onAllow = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onSamples = { samples(upcoming, s.notif).forEach { x -> graph.notifier.post(x.tag, x.channel, x.title, x.text, x.nav) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotifSettingsContent(
    n: NotifPrefs, granted: Boolean, upcoming: List<PlannedReminder>,
    onChange: (NotifPrefs) -> Unit, onAllow: () -> Unit = {}, onSamples: () -> Unit = {},
) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Notifications on this phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("Each phone has its own; both follow the same household plan.", style = small, color = muted)
        if (!granted) {
            Text("Notifications are off for Meal Prep, so none of these can show.", color = MaterialTheme.colorScheme.error, style = small)
            Button(onAllow) { Text("Allow notifications") }
        }
        ReminderToggle("Thaw reminder", "The evening before a dinner whose kit was frozen on Sunday: what to move to the fridge.",
            n.thaw, { onChange(n.copy(thaw = it)) }, n.thawAt) { onChange(n.copy(thawAt = it)) }
        ReminderToggle("How was dinner?", "The morning after each dinner, to rate it.",
            n.rate, { onChange(n.copy(rate = it)) }, n.rateAt) { onChange(n.copy(rateAt = it)) }
        ReminderToggle("Tonight's dinner", "On the day, with its cook card.",
            n.tonight, { onChange(n.copy(tonight = it)) }, n.tonightAt) { onChange(n.copy(tonightAt = it)) }
        ReminderToggle("Cart ready to review", "When a cart built on either phone is ready (checked about every hour).",
            n.cartReady, { onChange(n.copy(cartReady = it)) })
        ReminderToggle("Weekly staples reminder",
            if (n.staples) reminderSummary(n) else "A notification to check the staples before you shop.",
            n.staples, { onChange(n.copy(staples = it)) })
        if (n.staples) {
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WEEK_DAYS.forEach { d -> GardenChip(n.staplesDay == d, { onChange(n.copy(staplesDay = d)) }, Weeks.dayLabel(d.value % 7)) }
            }
            TimeButton("staples reminder", n.staplesAt) { onChange(n.copy(staplesAt = it)) }
        }
        Text("Coming up on this phone", style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        if (upcoming.isEmpty()) Text("Nothing yet. Reminders follow the dinners on This week (and a ready prep plan for thaws).",
            style = small, color = muted)
        upcoming.take(6).forEach { Text(upcomingLine(it), style = small) }
        OutlinedButton(onSamples, enabled = granted, modifier = Modifier.padding(top = 4.dp)) { Text("Show a sample of each now") }
    }
}

/** A switch row (the whole row toggles), and when on and timed, the time with a way to change it. */
@Composable
private fun ReminderToggle(label: String, detail: String, on: Boolean, onToggle: (Boolean) -> Unit, at: LocalTime? = null,
                           onTime: (LocalTime) -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Switch, onValueChange = onToggle),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, onCheckedChange = null)
    }
    if (on && at != null) TimeButton(label.lowercase(), at, onTime)
}

/** "At 8:00 PM · Change" → the platform time picker (12/24 h as the phone is set). */
@Composable
private fun TimeButton(what: String, at: LocalTime, onTime: (LocalTime) -> Unit) {
    val ctx = LocalContext.current
    TextButton({
        TimePickerDialog(ctx, { _, h, m -> onTime(LocalTime.of(h, m)) }, at.hour, at.minute, DateFormat.is24HourFormat(ctx)).show()
    }, Modifier.semantics { contentDescription = "Change the $what time, now ${time(at)}" }) { Text("At ${time(at)} · Change") }
}
