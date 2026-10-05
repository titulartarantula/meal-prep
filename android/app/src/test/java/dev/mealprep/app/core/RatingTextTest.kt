package dev.mealprep.app.core

import dev.mealprep.app.data.api.RatingNote
import dev.mealprep.app.data.api.RatingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingTextTest {
    @Test fun `never rated is null`() = assertNull(RatingText.summary(RatingSummary(timesCooked = 1)))

    @Test fun `average, company verdict and latest note`() {
        val r = RatingSummary(timesRated = 2, avgFamily = 4.5, lastFamily = 5, company = "yes",
            notes = listOf(RatingNote("less salt", "2026-09-29", "x")))
        assertEquals("Family 4.5/5 · Company: yes · “less salt”", RatingText.summary(r))
        assertEquals("Family 5/5", RatingText.summary(RatingSummary(timesRated = 1, avgFamily = 5.0)))
    }
}
