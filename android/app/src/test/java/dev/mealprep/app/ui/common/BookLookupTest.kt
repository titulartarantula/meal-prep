package dev.mealprep.app.ui.common

import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.api.BookHit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookLookupTest {
    private val asked = mutableListOf<String>()
    private var answer: ApiResult<List<BookHit>> = ApiResult.Ok(listOf(BookHit("Invented Bakes", authors = listOf("Ada Pepper"))))
    private val search: suspend (String) -> ApiResult<List<BookHit>> = { q -> asked += q; answer }

    @Test fun `asks once, 300 ms after the last keystroke`() = runTest {
        val l = BookLookup(backgroundScope, search)
        l.typed("I"); l.typed("In"); l.typed("Inv")
        advanceTimeBy(299); runCurrent()
        assertTrue(asked.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("Inv"), asked)
        assertEquals(listOf("Invented Bakes"), l.found.value.map { it.title })
        assertEquals("Ada Pepper", l.found.value.single().author)
        // the same query again (another space, other case) isn't asked twice
        l.typed("inv "); advanceTimeBy(400); runCurrent()
        assertEquals(listOf("Inv"), asked)
        // the last answer stays while the next one is on its way
        l.typed("Inve"); advanceTimeBy(100); runCurrent()
        assertEquals(1, l.found.value.size)
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf("Inv", "Inve"), asked)
    }

    @Test fun `under two characters nothing is asked and the rows go`() = runTest {
        val l = BookLookup(backgroundScope, search)
        l.typed("Inv"); advanceTimeBy(301); runCurrent()
        assertEquals(1, l.found.value.size)
        l.typed("I"); advanceTimeBy(301); runCurrent()
        assertTrue(l.found.value.isEmpty())
        l.typed(""); l.typed(" "); advanceTimeBy(301); runCurrent()
        assertEquals(listOf("Inv"), asked)
    }

    @Test fun `offline or an error is just no rows`() = runTest {
        val l = BookLookup(backgroundScope, search)
        answer = ApiResult.Err(ApiError.Unreachable)
        l.typed("Inv"); advanceTimeBy(301); runCurrent()
        assertTrue(l.found.value.isEmpty())
        answer = ApiResult.Err(ApiError.Http(500, null))
        l.typed("Inven"); advanceTimeBy(301); runCurrent()
        assertTrue(l.found.value.isEmpty())
        assertEquals(listOf("Inv", "Inven"), asked)
    }
}
