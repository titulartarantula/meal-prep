package dev.mealprep.app.ui.fridge

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.Http
import dev.mealprep.app.data.api.Suggestion
import dev.mealprep.app.fixture
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class SuggestTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-10-07")     // a Wednesday: Wed–Sat are left this week
    private val week = LocalDate.parse("2026-10-04")
    private val sug = Http.json.decodeFromString(Suggestion.serializer(), fixture("fridge_suggest.json"))
    @After fun tearDown() = env.close()

    @Test fun `texts`() {
        val p = sug.picks[0]
        assertEquals("Uses: 4 Eggs, 1 bag Spinach and Cilantro", SuggestLogic.uses(p))
        assertEquals("Still need: feta and red onion", SuggestLogic.stillNeed(p))
        assertEquals("Still need: chicken thigh, lime, jalapeno, tomatillo, green onion, avocado, tortilla, sour cream and 2 more",
            SuggestLogic.stillNeed(sug.picks[1]))
        assertEquals(listOf("⚠ Cilantro: last checked 9 days ago"), SuggestLogic.staleLines(p, sug))
        assertEquals("★ 4.5" to "Rated 4.5 out of 5", SuggestLogic.rating(p))
        assertEquals(null, SuggestLogic.rating(sug.picks[1]))
        assertEquals("Only 1 recipe in Recipes uses what's in the fridge.", SuggestLogic.thinText(sug.copy(thinLibrary = true, picks = sug.picks.take(1))))
        assertEquals("Add 2 to this week", SuggestLogic.addLabel(2, week, today))
        assertEquals("Add 1 to the week of Oct 25", SuggestLogic.addLabel(1, LocalDate.parse("2026-10-25"), today))
    }

    private suspend fun vm(): SuggestViewModel {
        env.on("GET", "/weeks/2026-10-04", body = """[{"id":1,"week":"2026-10-04","recipe_id":3,"day":4,"title":"Chili"}]""")
        env.on("POST", "/fridge/suggest", body = fixture("fridge_suggest.json"))
        return SuggestViewModel(env.repo, LocalDate.parse("2026-10-06"), { today }).also { v -> v.state.await { !it.loading && it.result != null } }
    }

    @Test fun `free nights from today on, people 4, all picks ticked`() = runTest {
        val v = vm()
        assertEquals(3, v.state.value.nights)                          // Wed, Fri, Sat (Thu has the Chili)
        assertEquals("""{"nights":3,"people":4,"week":"2026-10-04","exclude":[]}""", env.bodies("POST", "/fridge/suggest").single())
        assertEquals(setOf(5, 7), v.state.value.ticked)
    }

    @Test fun `suggest others leaves out the unticked picks`() = runTest {
        val v = vm()
        v.toggle(7)
        v.suggestOthers()
        v.state.await { !it.loading && env.count("POST", "/fridge/suggest") == 2 }
        assertEquals("""{"nights":3,"people":4,"week":"2026-10-04","exclude":[7]}""", env.bodies("POST", "/fridge/suggest").last())
        v.setPeople(2)
        v.state.await { env.count("POST", "/fridge/suggest") == 3 && !it.loading }
        assertTrue(env.bodies("POST", "/fridge/suggest").last().contains("\"people\":2"))
    }

    @Test fun `adding never puts a recipe on the week twice`() = runTest {
        val v = vm()
        env.on("GET", "/weeks/2026-10-04", body = """[{"id":1,"week":"2026-10-04","recipe_id":5,"day":null,"title":"Test Spinach Frittata"}]""")
        env.on("POST", "/weeks/2026-10-04/entries", body = """{"id":9,"week":"2026-10-04","recipe_id":7}""")
        v.addToWeek(); v.addToWeek()
        val done = v.state.await { it.done != null }.done!!
        assertEquals("Added 2 dinners to this week — put them on nights there.", done)
        assertEquals(listOf("""{"recipe_id":7}"""), env.bodies("POST", "/weeks/2026-10-04/entries"))
    }

    @Test fun `offline says suggestions need the home network`() = runTest {
        env.offline = true
        val v = SuggestViewModel(env.repo, week, { today })
        assertEquals(SuggestLogic.OFFLINE, v.state.await { it.error != null }.error)
    }

    @Config(fontScale = 2f)
    @Test fun `cards are checkboxes, the steppers 48 dp, at 200 percent the button stays`() {
        compose.setContent {
            SuggestContent(SuggestState(week, nights = 7, loading = false, result = sug, ticked = setOf(5, 7)), today, SuggestActions())
        }
        compose.onNode(hasText("Test Spinach Frittata", substring = true) and isToggleable()).assertIsOn()
        compose.onNodeWithContentDescription("More nights").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Rated 4.5 out of 5").assertExists()
        compose.onNodeWithText("⚠ Cilantro: last checked 9 days ago").assertExists()
        compose.onNodeWithText("Add 2 to this week").assertIsDisplayed()
    }
}
