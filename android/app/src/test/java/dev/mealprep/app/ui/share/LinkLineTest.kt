package dev.mealprep.app.ui.share

import dev.mealprep.app.core.ShareInput
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkLineTest {
    @Test fun `the share screen names where a link goes`() {
        assertEquals("NYT Cooking · 1015819-chili", linkLine(ShareInput.Link("https://cooking.nytimes.com/recipes/1015819-chili", "")))
        assertEquals("kitchen.example.com · apple-crumble",
            linkLine(ShareInput.Link("https://www.kitchen.example.com/recipes/apple-crumble/?utm_source=x#top", "")))
        assertEquals("kitchen.example.com", linkLine(ShareInput.Link("https://kitchen.example.com/", "")))
    }
}
