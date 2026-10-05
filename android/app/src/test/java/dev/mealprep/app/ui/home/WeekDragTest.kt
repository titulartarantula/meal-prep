package dev.mealprep.app.ui.home

import dev.mealprep.app.data.api.PlanEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekDragTest {
    private val chili = PlanEntry(21, "2026-10-11", 5, 2, title = "Chili")          // on Tue
    private val cookies = PlanEntry(23, "2026-10-11", 1, null, title = "Cookies")   // in the tray
    private val all = listOf(chili, cookies)

    @Test fun `the drag text names one of our entries`() {
        assertEquals("mealprep-entry:21", WeekDragText.of(21))
        assertEquals(21, WeekDragText.entryId("mealprep-entry:21"))
        assertNull(WeekDragText.entryId("21"))                          // plain text from another app
        assertNull(WeekDragText.entryId("mealprep-entry:x"))
        assertNull(WeekDragText.entryId(null))
    }

    @Test fun `dropping on a night places, on another night moves, on the tray unplaces`() {
        assertEquals(cookies to 4, dropMove(all, "mealprep-entry:23", Slot(4)))     // tray → Thu
        assertEquals(chili to 5, dropMove(all, "mealprep-entry:21", Slot(5)))       // Tue → Fri
        assertEquals(chili to null, dropMove(all, "mealprep-entry:21", Slot.TRAY))  // Tue → tray
    }

    @Test fun `dropping where it already is, or something else, does nothing`() {
        assertNull(dropMove(all, "mealprep-entry:21", Slot(2)))
        assertNull(dropMove(all, "mealprep-entry:23", Slot.TRAY))
        assertNull(dropMove(all, "mealprep-entry:99", Slot(1)))                     // another week's entry
        assertNull(dropMove(all, "hello", Slot(1)))
        assertNull(dropMove(all, null, Slot(1)))
    }

    @Test fun `drag state locks the week swipe and tracks the zone under the finger`() {
        val d = WeekDrag()
        assertFalse(d.dragging)
        d.started(); d.started()                                                    // every zone hears the start
        assertTrue(d.dragging)
        d.entered(Slot(3)); assertEquals(Slot(3), d.over)
        d.entered(Slot(4)); d.exited(Slot(3)); assertEquals(Slot(4), d.over)      // the next zone's enter can come first
        d.exited(Slot(4)); assertNull(d.over)
        d.entered(Slot.TRAY)
        d.ended()                                                                   // dropped (or let go outside)
        assertFalse(d.dragging); assertNull(d.over)
    }
}
