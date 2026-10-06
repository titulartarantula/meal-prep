package dev.mealprep.app.core

import dev.mealprep.app.data.api.Recipe
import dev.mealprep.app.data.api.RecipeSource
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

    @Test fun `a known author follows the book title`() {
        val withAuthor = r(8, "book", "Invented Bakes", "12").copy(sourceAuthor = "Ada Pepper")
        assertEquals("Invented Bakes · Ada Pepper, p. 12", Sources.label(withAuthor))
        assertEquals("Invented Bakes · Ada Pepper et al.",
            Sources.label(withAuthor.copy(sourceRef = null, sourceAuthor = "Ada Pepper, Basil Thyme, Rosemary Quill")))
        assertEquals("Unknown book, p. 3", Sources.label(r(9, "book", ref = "3").copy(sourceAuthor = "Ada Pepper")))
    }

    @Test fun `a named other source reads as its name and note`() {
        assertEquals("Mum's recipes", Sources.label(r(10, "other", "Mum's recipes")))
        assertEquals("Mum's recipes, the blue binder", Sources.label(r(11, "other", "Mum's recipes", " the blue binder ")))
        assertEquals("Mum's recipes, 12", Sources.label(r(12, "other", "Mum's recipes", "12")))   // a note, not a page
        assertEquals("Other", Sources.label(r(13, "other", ref = " ")))
        assertEquals("other:mum's recipes", Sources.key(r(14, "other", "  Mum's   Recipes ")))
        assertEquals("other:", Sources.key(r(15, "other")))
    }

    @Test fun `each named other source gets its own filter entry, A-Z with the books`() {
        val all = listOf(r(1, "book", "Invented Bakes"), r(2, "other", "Mum's recipes"), r(3, "other", "mum's  RECIPES"),
            r(4, "other", "Allotment Club"), r(5, "other"), r(6, "book"), r(7, "other", "Invented Bakes"), r(8, "nyt", source = "nyt"))
        assertEquals(listOf(
            Sources.Option(Sources.ALL, "All sources", 8), Sources.Option("nyt", "NYT Cooking", 1),
            Sources.Option("other:allotment club", "Allotment Club", 1), Sources.Option("book:invented bakes", "Invented Bakes", 1),
            Sources.Option("other:invented bakes", "Invented Bakes", 1), Sources.Option("other:mum's recipes", "Mum's recipes", 2),
            Sources.Option("book:", "Unknown book", 1), Sources.Option("other:", "Other", 1)), Sources.options(all))
    }

    @Test fun `the household's other names are suggested and keep their spelling`() {
        val sources = listOf(RecipeSource("book:Invented Bakes", "book", "Invented Bakes", "Invented Bakes", 2),
            RecipeSource("other:Mum's recipes", "other", "Mum's recipes", "Mum's recipes", 2),
            RecipeSource("other:allotment club", "other", "allotment  club", "allotment  club", 1),
            RecipeSource("other:", "other", label = "Other", count = 1))
        val names = Sources.otherNames(sources)
        assertEquals(listOf("allotment club", "Mum's recipes"), names)             // never a book, never the unnamed one
        assertEquals(names, Sources.suggestNames(names, ""))
        assertEquals(listOf("Mum's recipes"), Sources.suggestNames(names, "MUM"))
        assertEquals(emptyList<String>(), Sources.suggestNames(names, "mum's recipes"))   // already typed
        assertEquals("Mum's recipes", Sources.resolveName("  mum's   RECIPES ", names))
        assertEquals("Gran's cards", Sources.resolveName(" Gran's  cards", names))
        assertEquals(null, Sources.resolveName("  ", names))
    }
}
