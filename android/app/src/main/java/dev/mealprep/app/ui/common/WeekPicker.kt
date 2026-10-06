package dev.mealprep.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.mealprep.app.core.Weeks
import dev.mealprep.app.data.api.WeekSummary
import java.time.LocalDate

data class WeekOption(val week: LocalDate, val label: String, val detail: String?)

/** This week + the next count−1 weeks (the planning horizon: 4 in all); details ("2 recipes · cart sent") only when
 *  the server answered. */
fun weekOptions(today: LocalDate, summaries: List<WeekSummary>?, count: Int = Weeks.HORIZON): List<WeekOption> {
    val first = Weeks.weekStart(today)
    val byWeek = summaries.orEmpty().associateBy { LocalDate.parse(it.week) }
    return (0 until count).map { i ->
        val w = first.plusWeeks(i.toLong())
        val s = byWeek[w]
        val detail = s?.let {
            listOfNotNull(if (it.entries == 1) "1 recipe" else "${it.entries} recipes", "cart sent".takeIf { _ -> it.carted }).joinToString(" · ")
        }
        WeekOption(w, Weeks.weekChoiceLabel(w, today), detail)
    }
}

@Composable
fun WeekPicker(options: List<WeekOption>, selected: LocalDate?, onSelect: (LocalDate) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .selectable(o.week == selected, onClick = { onSelect(o.week) }, role = Role.RadioButton).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o.week == selected, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(o.label)
                    o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
