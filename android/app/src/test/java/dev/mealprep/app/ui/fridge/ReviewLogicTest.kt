package dev.mealprep.app.ui.fridge

import dev.mealprep.app.data.api.ApplyLine
import dev.mealprep.app.data.api.FridgeRead
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.fixture
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewLogicTest {
    private val read = Http.json.decodeFromString(FridgeRead.serializer(), fixture("fridge_read_ready.json"))
    private fun p(pid: String) = read.proposals.single { it.pid == pid }

    @Test fun `lines say the change in words, state first`() {
        assertEquals("Change Eggs: 6 → about 4 (estimate)", ReviewLogic.text(p("p1")))
        assertEquals("Spinach — still there (as of today)", ReviewLogic.text(p("p2")))
        assertEquals("Add · Whole milk · about 1 L (estimate) · top shelf", ReviewLogic.text(p("p3")))
        assertEquals("Add · Lemons · about 2 (estimate) · door · not sure", ReviewLogic.text(p("p4")))
        assertEquals("Remove Cilantro? Not seen in the photos", ReviewLogic.text(p("p5")))
    }

    @Test fun `an edited number is no longer an estimate`() {
        assertEquals("Change Eggs: 6 → 5", ReviewLogic.text(p("p1"), LineEdit(qty = 5.0, amountEdited = true)))
        assertEquals("Add · Milk · 2 L · top shelf", ReviewLogic.text(p("p3"), LineEdit("Milk", 2.0, "l", amountEdited = true)))
        assertEquals("Change Spinach: 1 bag → 2 bags", ReviewLogic.text(p("p2"), LineEdit(qty = 2.0, unit = "bag", amountEdited = true)))
    }

    @Test fun `defaults leave not seen and low confidence off`() {
        assertEquals(setOf("p1", "p2", "p3"), ReviewLogic.ticks(read))
    }

    @Test fun `the apply body carries every decision and only the edits`() {
        val edits = mapOf("p1" to LineEdit(qty = 5.0, amountEdited = true), "p3" to LineEdit("Milk", 1.0, "l"),
            "p4" to LineEdit(qty = 3.0, amountEdited = true))
        assertEquals(listOf(ApplyLine("p1", true, qty = 5.0), ApplyLine("p2", true), ApplyLine("p3", true, name = "Milk"),
            ApplyLine("p4", false), ApplyLine("p5", false)), ReviewLogic.lines(read, setOf("p1", "p2", "p3"), edits))
    }

    @Test fun `labels`() {
        assertEquals("Save 1 change", ReviewLogic.saveLabel(1)); assertEquals("Save 3 changes", ReviewLogic.saveLabel(3))
        assertEquals("Review 5 changes.", ReviewLogic.notificationText(read))
        assertEquals("Saved: 1 added, 1 changed, 1 still there.", ReviewLogic.resultText(mapOf("added" to 1, "changed" to 1, "seen" to 1, "removed" to 0)))
        assertEquals("Saved.", ReviewLogic.resultText(null))
    }
}
