package dev.mealprep.app.data

import dev.mealprep.app.TestEnv
import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.ApiResult
import dev.mealprep.app.data.cache.CachedDoc
import dev.mealprep.app.fixture
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepositoryTest {
    private val env = TestEnv()
    private val wk = LocalDate.parse("2026-10-14")   // a Wednesday: the repo asks for its Sunday
    @After fun tearDown() = env.close()

    @Test fun `offline falls back to the saved copy`() = runTest {
        env.on("GET", "/weeks/2026-10-11", body = fixture("week_home.json"))
        val fresh = env.repo.week(wk)
        assertEquals(3, fresh.value!!.size); assertNull(fresh.error); assertFalse(fresh.offline)
        env.offline = true
        val saved = env.repo.week(wk)
        assertEquals(3, saved.value!!.size)
        assertEquals(ApiError.Unreachable, saved.error)
        assertTrue(saved.offline)
        assertEquals(env.now, saved.fetchedAt)
    }

    @Test fun `404 on an optional read is cached as none`() = runTest {
        val p = env.repo.weekPrepPlan(wk)         // no route → 404
        assertNull(p.value); assertNull(p.error)
        env.offline = true
        val again = env.repo.weekPrepPlan(wk)
        assertNull(again.value); assertTrue(again.offline)
    }

    @Test fun `not configured falls back to cache`() = runTest {
        env.on("GET", "/weeks/2026-10-11", body = fixture("week_home.json"))
        env.repo.week(wk)
        env.token = ""
        val r = env.repo.week(wk)
        assertEquals(ApiError.NotConfigured, r.error)
        assertNotNull(r.value)
        assertEquals(ApiError.NotConfigured, (env.repo.removeEntry(1) as ApiResult.Err).error)
    }

    @Test fun `unreadable saved copy is ignored`() = runTest {
        env.db.cache().put(CachedDoc("week:2026-10-11", "{not json", 1L))
        env.offline = true
        val r = env.repo.week(wk)
        assertNull(r.value); assertEquals(ApiError.Unreachable, r.error)
    }

    @Test fun `writes send the right bodies`() = runTest {
        env.on("PATCH", "/plan/23", code = 204)
        env.on("PUT", "/plan/21/rating", code = 204)
        env.repo.placeEntry(23, null)
        env.repo.placeEntry(23, 4)
        env.repo.setRating(21, 4, "maybe", "  ")
        assertEquals(listOf("""{"day":null}""", """{"day":4}"""), env.bodies("PATCH", "/plan/23"))
        assertEquals(listOf("""{"family":4,"company":"maybe"}"""), env.bodies("PUT", "/plan/21/rating"))
    }

    @Test fun `markOnce is true only the first time`() = runTest {
        assertTrue(env.repo.markOnce("cart-ready:7"))
        assertFalse(env.repo.markOnce("cart-ready:7"))
    }

    @Test fun `prune removes old cache rows but keeps once markers`() = runTest {
        env.db.cache().put(CachedDoc("week:old", "[]", 1L))
        env.db.cache().put(CachedDoc("once:cart-ready:7", "1", 1L))
        env.repo.prune(java.time.Duration.ofDays(1))
        assertNull(env.db.cache().get("week:old"))
        assertTrue(env.repo.isMarked("cart-ready:7"))
        assertFalse(env.repo.markOnce("cart-ready:7"))
    }
}
