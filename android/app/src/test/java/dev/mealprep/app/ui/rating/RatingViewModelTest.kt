package dev.mealprep.app.ui.rating

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.RatingNote
import dev.mealprep.app.data.api.RatingSummary
import dev.mealprep.app.fixture
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RatingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val wk = LocalDate.parse("2026-10-11")
    private var changed = 0
    private val vms = mutableListOf<RatingViewModel>()

    @Before fun setUp() {
        env.on("GET", "/weeks/2026-10-11", body = fixture("week_home.json"))
        env.on("GET", "/recipes/6", body = fixture("recipe_3.json"))
        env.on("GET", "/recipes/5", body = fixture("recipe_3.json"))
        env.on("PUT", "/plan/22/rating", code = 204)
        env.on("PUT", "/plan/21/rating", code = 204)
        env.on("DELETE", "/plan/22/rating", code = 204)
        env.on("PUT", "/recipes/6/company", body = """{"company":"yes"}""")
    }
    @After fun tearDown() {
        runBlocking { withTimeout(5_000) { vms.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } } }
        env.close()
    }

    private fun vm(entry: Int) = RatingViewModel(env.repo, entry, wk) { changed++ }.also { vms += it }

    @Test fun `an already rated night is prefilled`() = runTest {
        val s = vm(22).state.await { !it.loading }
        assertEquals("Fish soup", s.title); assertEquals("Thursday", s.night)
        assertEquals(4, s.family); assertEquals("less salt", s.note)
        assertNull(s.company)   // the recipe's verdict (not decided), not the old rating's "maybe"
        assertTrue(s.existing)
    }

    @Test fun `saving sends the score and note, and an Owner's changed verdict apart`() = runTest {
        val vm = vm(22); vm.state.await { !it.loading && it.summary != null }
        vm.setFamily(5); vm.setCompany("yes"); vm.setNote("kids loved it")
        vm.save()
        vm.state.await { it.saved }
        assertEquals("""{"family":5,"note":"kids loved it"}""", env.bodies("PUT", "/plan/22/rating").single())
        assertEquals("""{"company":"yes"}""", env.bodies("PUT", "/recipes/6/company").single())
        assertEquals(1, changed)
    }

    @Test fun `tapping the chosen company verdict clears it, an unchanged verdict isn't sent, blank note is left out`() = runTest {
        val vm = vm(22); vm.state.await { !it.loading && it.summary != null }
        vm.setCompany("yes"); vm.setCompany("yes"); vm.setNote("   ")
        assertNull(vm.state.value.company)
        vm.save(); vm.state.await { it.saved }
        assertEquals("""{"family":4}""", env.bodies("PUT", "/plan/22/rating").single())
        assertTrue(env.bodies("PUT", "/recipes/6/company").isEmpty())
    }

    private val perPersonWeek = """[{"id": 22, "week": "2026-10-11", "recipe_id": 6, "day": 4, "title": "Fish soup",
        "rating": {"family": 4, "note": "less salt", "rated_at": "2026-10-16T08:00:00+00:00"},
        "my_rating": {"family": 4, "note": "less salt", "rated_at": "2026-10-16T08:00:00+00:00"},
        "ratings": [{"member_id": 3, "name": "Sam", "kind": "account", "family": 4, "note": "less salt", "rated_at": "x"},
                    {"member_id": 4, "name": "Alex", "kind": "account", "family": 3, "rated_at": "x"},
                    {"member_id": 9, "name": "Pip", "kind": "child", "family": 5, "rated_at": "x", "entered_by": 3, "entered_by_name": "Sam"}],
        "rating_avg": 4.0, "rating_count": 3}]"""
    private val members = """[{"id": 3, "name": "Sam", "kind": "account"}, {"id": 4, "name": "Alex", "kind": "account"},
        {"id": 9, "name": "Pip", "kind": "child"}]"""
    private val parent = dev.mealprep.app.data.settings.Account(name = "Sam", memberId = 3,
        permissions = setOf("rate.self", "rate.for_children", "plan.read"))

    @Test fun `a parent rates for a child profile, sees the others, and can't set the company verdict`() = runTest {
        env.on("GET", "/weeks/2026-10-11", body = perPersonWeek)
        env.on("GET", "/household/members", body = members)
        val vm = RatingViewModel(env.repo, 22, wk, parent).also { vms += it }
        var s = vm.state.await { !it.loading && it.raters.size == 2 }
        assertEquals(listOf(Rater(null, "Me"), Rater(9, "Pip")), s.raters)
        assertEquals(4, s.family); assertTrue(s.existing); assertEquals("Your score", s.whose)
        assertEquals(listOf("Alex", "Pip"), s.others.map { it.name })
        assertFalse(s.canSetCompany)
        vm.setCompany("yes"); assertNull(vm.state.value.company)
        vm.setWho(9)
        s = vm.state.value
        assertEquals(5, s.family); assertEquals("", s.note); assertTrue(s.existing); assertEquals("Pip's score", s.whose)
        assertEquals(listOf("Sam", "Alex"), s.others.map { it.name })
        vm.setFamily(2); vm.save(); vm.state.await { it.saved }
        assertEquals("""{"family":2,"member_id":9}""", env.bodies("PUT", "/plan/22/rating").single())
        assertTrue(env.bodies("PUT", "/recipes/6/company").isEmpty())
    }

    @Test fun `removing a child's rating names the child, and without rate_for_children there is no Rating for`() = runTest {
        env.on("GET", "/weeks/2026-10-11", body = perPersonWeek)
        env.on("GET", "/household/members", body = members)
        val vm = RatingViewModel(env.repo, 22, wk, parent).also { vms += it }
        vm.state.await { !it.loading && it.raters.size == 2 }
        vm.setWho(9); vm.clear(); vm.state.await { it.saved }
        assertEquals("9", env.requests.single { it.method == "DELETE" }.url.queryParameter("member_id"))

        val alex = dev.mealprep.app.data.settings.Account(name = "Alex", memberId = 4, permissions = setOf("rate.self"))
        val s = RatingViewModel(env.repo, 22, wk, alex).also { vms += it }.state.await { !it.loading && it.summary != null }
        assertEquals(1, s.raters.size); assertEquals(3, s.family); assertTrue(s.existing)
        assertEquals(listOf("Sam", "Pip"), s.others.map { it.name })
        assertEquals(1, env.requests.count { it.url.encodedPath == "/household/members" })   // only the parent asked
    }

    @Test fun `signed in, a failed save doesn't talk about the home network`() {
        assertFalse(ratingMessage(dev.mealprep.app.data.api.ApiError.Unreachable, signedIn = true).contains("home network"))
    }

    @Test fun `family score is required`() = runTest {
        val vm = vm(21); vm.state.await { !it.loading }
        assertFalse(vm.state.value.existing)
        vm.save()
        assertEquals("Pick 1 to 5 first.", vm.state.value.error)
        assertTrue(env.bodies("PUT", "/plan/21/rating").isEmpty())
    }

    @Test fun `clearing deletes the rating`() = runTest {
        val vm = vm(22); vm.state.await { !it.loading }
        vm.clear()
        val s = vm.state.await { it.saved }
        assertEquals(1, env.bodies("DELETE", "/plan/22/rating").size)
        assertFalse(s.existing); assertNull(s.family)
        assertEquals(1, changed)
    }

    @Test fun `away from home the saved copy prefills, and saving says it needs the home network`() = runTest {
        vm(22).state.await { !it.loading }                      // the week's copy is saved
        env.offline = true
        val vm = vm(22)
        val s = vm.state.await { !it.loading }
        assertEquals(4, s.family); assertTrue(s.offlineSince != null)
        vm.setFamily(3); vm.save()
        assertEquals(ratingMessage(dev.mealprep.app.data.api.ApiError.Unreachable), vm.state.await { it.error != null && !it.saving }.error)
        assertTrue(vm.state.value.error!!.contains("home network"))
        assertEquals(0, changed)
    }

    @Test fun `a dinner no longer in the plan says so`() = runTest {
        val s = vm(99).state.await { !it.loading }
        assertEquals("That dinner isn't in the plan any more.", s.error)
    }

    @Test fun `the screen asks about the night, selects the score and shows earlier notes`() {
        var family: Int? = null; var saved = 0
        val s = RatingState(title = "Chili", night = "Tuesday", family = 4, existing = true, loading = false,
            summary = RatingSummary(timesRated = 2, avgFamily = 4.5, notes = listOf(RatingNote("less salt", "2026-10-06"))))
        var cleared = 0
        compose.setContent { RatingContent(s, { family = it }, {}, {}, { saved++ }, { cleared++ }) }
        compose.onNodeWithText("How was Tuesday's Chili?").assertExists()
        compose.onNodeWithContentDescription("4 out of 5").assertIsSelected()
        compose.onNodeWithContentDescription("5 out of 5").performClick()
        assertEquals(5, family)
        compose.onNodeWithText("Update rating").performScrollTo().performClick()
        assertEquals(1, saved)
        compose.onNodeWithText("“less salt” — Oct 6").assertExists()
        // Removing asks first (the screen closes once it's gone).
        compose.onNodeWithText("Remove rating").performScrollTo().performClick()
        compose.onNodeWithText("Remove this rating?").assertExists()
        compose.onNodeWithText("Keep it").performClick()
        assertEquals(0, cleared)
        compose.onNodeWithText("Remove rating").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(1, cleared)
    }
}
