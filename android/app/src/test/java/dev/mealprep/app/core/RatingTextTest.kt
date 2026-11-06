package dev.mealprep.app.core

import dev.mealprep.app.data.api.PersonRating
import dev.mealprep.app.data.api.PersonSummary
import dev.mealprep.app.data.api.RatingNote
import dev.mealprep.app.data.api.RatingSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RatingTextTest {
    @Test fun `never rated is null`() = assertNull(RatingText.summary(RatingSummary(timesCooked = 1)))

    @Test fun `average from how many ratings and the latest note, without the company verdict`() {
        val r = RatingSummary(timesRated = 2, avgFamily = 4.5, lastFamily = 5, company = "yes", ratingCount = 3,
            notes = listOf(RatingNote("less salt", "2026-09-29", "x")))
        assertEquals("Average 4.5/5 from 3 ratings · “less salt”", RatingText.summary(r))
        // older servers have no rating_count: the times rated stand in
        assertEquals("Average 5/5 from 1 rating", RatingText.summary(RatingSummary(timesRated = 1, avgFamily = 5.0)))
    }

    @Test fun `good for company is Yes, No or not decided (an old maybe too)`() {
        assertEquals("Good for company: Yes (set by Sam)", RatingText.company(RatingSummary(company = "yes", companySetByName = "Sam")))
        assertEquals("Good for company: No", RatingText.company(RatingSummary(company = "no")))
        assertEquals("Good for company: not decided yet", RatingText.company(RatingSummary(company = "maybe")))
        assertEquals("Good for company: not decided yet", RatingText.company(RatingSummary()))
    }

    @Test fun `people, notes and times cooked`() {
        assertEquals("Sam (you): 4/5 · “less salt”", RatingText.personLine(PersonRating(3, "Sam", "account", 4, "less salt"), me = 3))
        assertEquals("Pip: 5/5 · entered by Sam", RatingText.personLine(PersonRating(9, "Pip", "child", 5, enteredByName = "Sam"), me = 3))
        assertEquals("Alex: 3/5 last time · average 3.5 from 2",
            RatingText.personSummary(PersonSummary(4, "Alex", "account", 3, avg = 3.5, count = 2), me = 3))
        assertEquals("Family: 4/5", RatingText.personSummary(PersonSummary(null, "Family", "family", 4, avg = 4.0, count = 1), me = 3))
        assertEquals("“kids loved it” — Sam (you), Oct 6", RatingText.note(RatingNote("kids loved it", "2026-10-06", memberId = 3, name = "Sam"), 3) { "Oct 6" })
        assertEquals("“imported”", RatingText.note(RatingNote("imported"), 3) { it })
        assertEquals("rated 4.5/5 by 2", RatingText.cooked(4.5, 2, 4)); assertEquals("rated 4/5", RatingText.cooked(4.0, 1, 4))
        assertEquals("rated 3/5", RatingText.cooked(null, 0, 3)); assertNull(RatingText.cooked(null, 0, null))
    }

    @Test fun `TalkBack hears out of 5, not slash`() {
        assertEquals("Average 4.5 out of 5 from 2 ratings", RatingText.spoken("Average 4.5/5 from 2 ratings"))
        assertEquals("Rating: 5 out of 5", RatingText.spoken("Rating: 5/5"))
        assertEquals("Week of Oct 4 · Wed · rated 3 out of 5", RatingText.spoken("Week of Oct 4 · Wed · rated 3/5"))
        assertEquals("1/50", RatingText.spoken("1/50"))
    }
}
