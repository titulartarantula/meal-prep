package dev.mealprep.app.ui.fridge

import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.FridgeItem
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FridgeLogicTest {
    private val today = LocalDate.parse("2026-10-07")
    private fun item(qty: Double? = 6.0, unit: String? = null, asOf: String = "2026-10-06", days: Int = 1, est: Boolean = false,
                     soon: Boolean = false, useBy: String? = null, name: String = "Eggs") =
        FridgeItem(1, name, qty = qty, unit = unit, amountAsOf = asOf, daysSinceChecked = days, qtyEstimated = est,
            useSoon = soon, useBy = useBy, soon = soon)

    @Test fun `amounts and titles`() {
        assertEquals("Eggs · 6", FridgeLogic.title(item()))
        assertEquals("Milk · 2 cups", FridgeLogic.title(item(2.0, "cup", name = "Milk")))
        assertEquals("Milk · 1½ L", FridgeLogic.title(item(1.5, "l", name = "Milk")))
        assertEquals("Cilantro · 2 bunches", FridgeLogic.title(item(2.0, "bunch", name = "Cilantro")))
        assertEquals("Spinach · about 1 bag", FridgeLogic.title(item(1.0, "bag", est = true, name = "Spinach")))
        assertEquals("Yogurt", FridgeLogic.title(item(null, name = "Yogurt")))
        assertEquals("Eggs · 0", FridgeLogic.title(item(0.0)))
    }

    @Test fun `as of, stale and estimated`() {
        assertEquals("as of yesterday · use by Oct 25", FridgeLogic.details(item(useBy = "2026-10-25"), today))
        assertEquals("as of Oct 6", FridgeLogic.details(item(), today.plusDays(2)))
        assertEquals("as of today", FridgeLogic.details(item(asOf = "2026-10-07", days = 0), today))
        assertEquals("as of yesterday", FridgeLogic.asOf(item(), today))
        assertEquals("as of Oct 2", FridgeLogic.asOf(item(asOf = "2026-10-02", days = 5), today))
        assertFalse(FridgeLogic.stale(item(days = 7)))
        assertEquals("last checked 9 days ago", FridgeLogic.asOf(item(asOf = "2026-09-28", days = 9), today))
        assertEquals("Use soon", FridgeLogic.details(item(asOf = "2026-09-28", days = 9, soon = true), today))
        assertEquals("as of yesterday · from a photo · Use soon", FridgeLogic.details(item(est = true, soon = true, useBy = "2026-10-25"), today))
        assertNull(FridgeLogic.tag(item()))
    }

    @Test fun `TalkBack reads the row as one sentence`() {
        assertEquals("Eggs, 6, as of October 6, use soon", FridgeLogic.description(item(asOf = "2026-10-06", soon = true), today.plusDays(1)))
        assertEquals("Spinach, about 1 bag, as of today, from a photo, use by October 9",
            FridgeLogic.description(item(1.0, "bag", asOf = "2026-10-07", days = 0, est = true, useBy = "2026-10-09", name = "Spinach"), today))
        assertEquals("Yogurt, last checked 9 days ago", FridgeLogic.description(item(null, days = 9, name = "Yogurt"), today))
    }

    @Test fun `amounts typed`() {
        assertEquals(1.5, FridgeLogic.parseAmount(" 1,5 ").getOrNull())
        assertEquals(0.0, FridgeLogic.parseAmount("0").getOrNull())
        assertNull(FridgeLogic.parseAmount("").getOrNull())
        assertTrue(FridgeLogic.parseAmount("lots").isFailure)
        assertTrue(FridgeLogic.parseAmount("-1").isFailure)
        assertTrue(FridgeLogic.parseAmount("10001").isFailure)
    }

    @Test fun `a form from an item and the write messages`() {
        assertEquals(FridgeForm(1, "Eggs", "6", "", false, LocalDate.parse("2026-10-25")), FridgeForm.of(item(useBy = "2026-10-25")))
        assertEquals(FridgeForm(1, "Milk", "1.5", "l"), FridgeForm.of(item(1.5, "l", name = "Milk")))
        assertEquals(FridgeLogic.OFFLINE, FridgeLogic.writeMessage(ApiError.Unreachable))
        assertEquals("Already in the fridge: 6 Eggs", FridgeLogic.writeMessage(ApiError.Exists("Already in the fridge: 6 Eggs", 1)))
    }
}
