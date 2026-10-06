package dev.mealprep.app.core

import dev.mealprep.app.data.api.BookHit
import dev.mealprep.app.data.api.RecipeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BooksTest {
    private val yours = Books.yours(listOf(
        RecipeSource("nyt", "nyt", label = "NYT Cooking", count = 2),
        RecipeSource("book:Invented Bakes", "book", "Invented Bakes", "Invented Bakes", 2, author = "Ada Pepper", isbn = "9780000000017"),
        RecipeSource("book:A Made-Up Garden", "book", "A Made-Up Garden", "A Made-Up Garden", 1),
        RecipeSource("book:", "book", label = "Unknown book", count = 1)))
    private val found = Books.found(listOf(
        BookHit("Invented Bakes!", authors = listOf("Ada Pepper"), year = 2011),     // already one of yours, with an author
        BookHit("A Made-Up Garden", authors = listOf("Basil Thyme"), year = 2001),   // yours has no author: offered
        BookHit("Invented Breads", "Loaves", listOf("Rosemary Quill", "Ada Pepper", "Basil Thyme", "Juniper Ash"), 1987, "0000000027")))

    @Test fun `your books come first and match what is typed, search rows follow`() {
        assertEquals(listOf("Invented Bakes"), yours.filter { it.author != null }.map { it.title })
        val s = Books.suggest(yours, found, BookChoice("inv"))
        assertEquals(listOf("Invented Bakes"), s.yours.map { it.title })
        assertTrue(s.yours.all { it.mine })
        assertEquals(listOf("A Made-Up Garden", "Invented Breads"), s.found.map { it.title })
        val breads = s.found[1]
        assertEquals("Invented Breads: Loaves", breads.display)
        assertEquals("Rosemary Quill, Ada Pepper, Basil Thyme · 1987", breads.secondary)
        assertEquals("by Rosemary Quill, Ada Pepper, Basil Thyme, 1987", breads.spoken)
        // empty field: all your books, no search rows
        val empty = Books.suggest(yours, found, BookChoice(""))
        assertEquals(listOf("A Made-Up Garden", "Invented Bakes"), empty.yours.map { it.title })
        assertTrue(empty.found.isEmpty())
        // the exact title typed isn't offered again
        assertTrue(Books.suggest(yours, emptyList(), BookChoice("invented bakes")).isEmpty)
    }

    @Test fun `a picked book hides the rows until the title is changed`() {
        val picked = found[2].choice
        assertTrue(Books.suggest(yours, found, picked).isEmpty)
        val sameBook = picked.typed("invented  breads")
        assertEquals("Rosemary Quill, Ada Pepper, Basil Thyme", sameBook.author)
        assertTrue(sameBook.picked)
        val other = picked.typed("Invented Bre")
        assertEquals(BookChoice("Invented Bre"), other)
        assertFalse(Books.suggest(yours, found, other).isEmpty)
    }

    @Test fun `a saved book keeps its details, an unknown one has none`() {
        assertTrue(BookChoice.saved("Invented Bakes", "Ada Pepper", null).picked)
        assertFalse(BookChoice.saved("Invented Bakes", null, null).picked)
        assertFalse(BookChoice.saved(null, "Ada Pepper", null).picked)
    }

    @Test fun `resolve fills a typed household book's details and drops blanks`() {
        assertEquals(BookChoice("Invented Bakes", "Ada Pepper", "9780000000017"), Books.resolve(BookChoice(" invented   bakes "), yours))
        assertEquals(BookChoice("A Made-Up Garden"), Books.resolve(BookChoice("A Made-Up Garden"), yours))
        assertEquals(BookChoice(), Books.resolve(BookChoice("   "), yours))
        assertEquals(BookChoice("Invented Breads", "R. Quill", null), Books.resolve(BookChoice(" Invented Breads", "R. Quill", picked = true), yours))
    }

    @Test fun `queries and names`() {
        assertNull(Books.query(" a "))
        assertEquals("sa lt", Books.query("  sa   lt "))
        assertTrue(Books.same("Salt, Fat, Acid, Heat", "salt fat acid heat"))
        assertTrue(Books.same("Crème & Brûlée", "creme and brulee"))
        assertNull(Books.authors(listOf(" ", "")))
        assertEquals("Ada Pepper", Books.shortAuthor(" Ada Pepper "))
        assertEquals("Ada Pepper, Basil Thyme", Books.shortAuthor("Ada Pepper, Basil Thyme"))
        assertEquals("Rosemary Quill et al.", Books.shortAuthor("Rosemary Quill, Ada Pepper, Basil Thyme"))
        assertNull(Books.shortAuthor(null))
    }
}
