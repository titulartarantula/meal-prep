package dev.mealprep.app.ui.common

import dev.mealprep.app.data.api.WeekSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekPickerTest {
    private val today = LocalDate.parse("2026-10-07")

    @Test fun `week details say cart sent, or that the recipes changed since the cart`() {
        val o = weekOptions(today, listOf(
            WeekSummary("2026-10-04", 0),
            WeekSummary("2026-10-11", 2, carted = true),
            WeekSummary("2026-10-18", 1, carted = false, cartStale = true),
        ))
        assertEquals(listOf("0 recipes", "2 recipes · cart sent", "1 recipe · recipes changed since cart", null), o.map { it.detail })
    }
}
