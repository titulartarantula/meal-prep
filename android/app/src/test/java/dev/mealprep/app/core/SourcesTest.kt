package dev.mealprep.app.core

import dev.mealprep.app.data.api.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test

class SourcesTest {
    private fun r(id: Int, kind: String?, title: String? = null, ref: String? = null, source: String = "photo", url: String? = null) =
        Recipe(id, "Recipe $id", source, url, sourceKind = kind, sourceTitle = title, sourceRef = ref)

    @Test fun `labels read like a citation`() {
        assertEquals("NYT Cooking", Sources.label(r(1, "nyt", source = "nyt")))
        assertEquals("Invented Bakes, p. 123", Sources.label(r(2, "book", "Invented Bakes", "123")))
        assertEquals("Invented Bakes, pp. 12–13", Sources.label(r(3, "book", "Invented Bakes", "12 - 13")))
        assertEquals("Invented Bakes, near the back", Sources.label(r(4, "book", "Invented Bakes", "near the back")))
        assertEquals("Unknown book", Sources.label(r(5, "book")))
        assertEquals("Unknown book, p. 7", Sources.label(r(6, "book", ref = "7")))
        assertEquals("Other", Sources.label(r(7, "other", source = "x")))
    }

    @Test fun `a saved copy from before sources still has a kind`() {
        assertEquals(Sources.NYT, Sources.kind(r(1, null, source = "nyt", url = "https://cooking.nytimes.com/recipes/1-x")))
        assertEquals(Sources.BOOK, Sources.kind(r(2, null)))
        assertEquals("Unknown book", Sources.label(r(2, null)))
    }

    @Test fun `filter options list each source once with counts`() {
        val all = listOf(r(1, "nyt", source = "nyt"), r(2, "book", "Invented Bakes"), r(3, "book", "invented  bakes "),
            r(4, "book", "A Made-Up Garden"), r(5, "book"), r(6, "nyt", source = "nyt"))
        assertEquals(listOf(
            Sources.Option(Sources.ALL, "All sources", 6), Sources.Option("nyt", "NYT Cooking", 2),
            Sources.Option("book:a made-up garden", "A Made-Up Garden", 1), Sources.Option("book:invented bakes", "Invented Bakes", 2),
            Sources.Option("book:", "Unknown book", 1)), Sources.options(all))
        assertEquals("book:invented bakes", Sources.key(all[2]))
    }

    @Test fun `book suggestions match what is typed`() {
        val books = listOf("Invented Bakes", "A Made-Up Garden", "invented bakes")
        assertEquals(listOf("A Made-Up Garden", "Invented Bakes"), Sources.suggest(books, ""))
        assertEquals(listOf("Invented Bakes"), Sources.suggest(books, "bak"))
        assertEquals(emptyList<String>(), Sources.suggest(books, "Invented Bakes"))   // already typed in full
    }
}
