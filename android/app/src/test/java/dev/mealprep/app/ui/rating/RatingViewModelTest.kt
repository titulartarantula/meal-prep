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
    }
    @After fun tearDown() {
        runBlocking { withTimeout(5_000) { vms.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } } }
        env.close()
    }

    private fun vm(entry: Int) = RatingViewModel(env.repo, entry, wk) { changed++ }.also { vms += it }

    @Test fun `an already rated night is prefilled`() = runTest {
        val s = vm(22).state.await { !it.loading }
        assertEquals("Fish soup", s.title); assertEquals("Thursday", s.night)
        assertEquals(4, s.family); assertEquals("maybe", s.company); assertEquals("less salt", s.note)
        assertTrue(s.existing)
    }

    @Test fun `saving sends family, company and note`() = runTest {
        val vm = vm(22); vm.state.await { !it.loading }
        vm.setFamily(5); vm.setCompany("yes"); vm.setNote("kids loved it")
        vm.save()
        vm.state.await { it.saved }
        assertEquals("""{"family":5,"company":"yes","note":"kids loved it"}""", env.bodies("PUT", "/plan/22/rating").single())
        assertEquals(1, changed)
    }

    @Test fun `tapping the chosen company verdict clears it, blank note is left out`() = runTest {
        val vm = vm(22); vm.state.await { !it.loading }
        vm.setCompany("maybe"); vm.setNote("   ")
        assertNull(vm.state.value.company)
        vm.save(); vm.state.await { it.saved }
        assertEquals("""{"family":4}""", env.bodies("PUT", "/plan/22/rating").single())
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
