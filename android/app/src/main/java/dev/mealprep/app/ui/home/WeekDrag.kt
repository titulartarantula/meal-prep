package dev.mealprep.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mealprep.app.data.api.PlanEntry

/**
 * Dragging a recipe on "This week" (Android drag and drop, started by holding a recipe).
 *
 * The drag carries "mealprep-entry:<id>" as plain text, so text dragged in from another app is never taken for a
 * recipe. Drop zones are the seven nights and the tray ("No night yet").
 */
object WeekDragText {
    const val PREFIX = "mealprep-entry:"
    fun of(entryId: Int) = "$PREFIX$entryId"
    fun entryId(text: String?): Int? = text?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toIntOrNull()
}

/** A drop zone: a night (0 = Sun … 6 = Sat) or the tray (null). */
data class Slot(val day: Int?) {
    companion object { val TRAY = Slot(null) }
}

/** What a drop does: (entry, new night or null for the tray), or null when it isn't one of this week's recipes or
 *  it was dropped where it already is. */
fun dropMove(all: List<PlanEntry>, text: String?, slot: Slot): Pair<PlanEntry, Int?>? {
    val id = WeekDragText.entryId(text) ?: return null
    val e = all.firstOrNull { it.id == id } ?: return null
    return if (e.day == slot.day) null else e to slot.day
}

/**
 * Shared by all week pages: while a recipe is held, the week swipe is off (the drag has priority) and the drop
 * zones are outlined, the one under the finger filled and labelled. Driven by the drop targets' callbacks, which
 * the system sends to every zone when a drag starts and ends (also when it is dropped outside any zone).
 */
class WeekDrag {
    var dragging by mutableStateOf(false)
        private set
    var over by mutableStateOf<Slot?>(null)
        private set

    fun started() { dragging = true }
    fun entered(slot: Slot) { over = slot }
    fun exited(slot: Slot) { if (over == slot) over = null }
    fun ended() { dragging = false; over = null }
}
