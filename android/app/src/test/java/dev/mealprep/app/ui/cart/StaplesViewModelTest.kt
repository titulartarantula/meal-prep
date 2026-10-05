package dev.mealprep.app.ui.cart

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.data.api.Staple
import dev.mealprep.app.fixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StaplesViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val env = TestEnv()

    @Before fun setUp() = env.on("GET", "/staples", body = fixture("staples.json"))
    @After fun tearDown() = env.close()

    private suspend fun loaded() = StaplesViewModel(env.repo).also { vm -> vm.state.await { !it.loading } }

    @Test fun `adding sends the staple and reloads`() = runTest {
        env.on("POST", "/staples", code = 201, body = """{"id":4,"name":"bread","qty":2.0,"weekly":true,"position":3}""")
        val vm = loaded()
        assertTrue(vm.save(StapleForm(name = " bread ", amount = "2", unit = " ", weekly = true)))
        assertEquals("""{"name":"bread","qty":2.0,"weekly":true}""", env.awaitBody("POST", "/staples"))
        vm.state.await { !it.busy }
        assertTrue(env.count("GET", "/staples") >= 2)
    }

    @Test fun `editing sends every field and clears the amount when emptied`() = runTest {
        env.on("PATCH", "/staples/3", body = """{"id":3,"name":"lemonade","weekly":true}""")
        val vm = loaded()
        assertTrue(vm.save(StapleForm(3, "lemonade", amount = "", unit = "", weekly = true)))
        assertEquals("""{"name":"lemonade","qty":null,"unit":null,"weekly":true}""", env.awaitBody("PATCH", "/staples/3"))
    }

    @Test fun `a blank name or a wrong amount is caught before sending`() = runTest {
        val vm = loaded()
        assertFalse(vm.save(StapleForm(name = "  ")))
        assertEquals("Give the staple a name.", vm.state.value.message)
        assertFalse(vm.save(StapleForm(name = "milk", amount = "lots")))
        assertEquals("The amount must be a number, like 1 or 2.5.", vm.state.value.message)
        assertEquals(1.5, parseAmount("1,5").getOrNull())
        assertEquals(null, parseAmount(" ").getOrNull())
        assertTrue(parseAmount("0").isFailure)
        assertEquals(0, env.count("POST", "/staples"))
    }

    @Test fun `moving down sends the new position and shows it at once`() = runTest {
        env.onGated("PATCH", "/staples/1", java.util.concurrent.CountDownLatch(1), """{"id":1,"name":"2% milk"}""")
        val vm = loaded()
        vm.move(1, 1)
        assertEquals(listOf(2, 1, 3), vm.state.value.staples.map { it.id })
        assertEquals("""{"position":1}""", env.awaitBody("PATCH", "/staples/1"))
        vm.move(3, 1)                                                     // already last: nothing to do
        assertEquals(1, env.requests.count { it.method == "PATCH" })
    }

    @Test fun `deleting`() = runTest {
        env.on("DELETE", "/staples/2", code = 204)
        loaded().delete(2)
        env.awaitBody("DELETE", "/staples/2")
    }

    @Test fun `off the home network a change says it needs the home network`() = runTest {
        val vm = loaded()
        env.offline = true
        vm.save(StapleForm(name = "bread"))
        val s = vm.state.await { it.message != null }
        assertEquals("Changing staples needs the home network (or WireGuard).", s.message)
        assertEquals(3, s.staples.size)                                      // the saved copy stays
    }

    @Test fun `screen lists staples with labelled move buttons and edits on tap`() {
        val staples = listOf(Staple(1, "2% milk", 1.0), Staple(2, "flour", 2.0, "kg", weekly = false))
        var moved: Pair<Int, Int>? = null
        var saved: StapleForm? = null
        compose.setContent {
            StaplesContent(StaplesState(staples, loading = false), onSave = { saved = it; true }, onDelete = {},
                onMove = { id, by -> moved = id to by }, onDone = {})
        }
        compose.onNodeWithText("2% milk · 1 pack").assertExists()
        compose.onNodeWithText("flour · 2 kg").assertExists()
        compose.onNodeWithText("Only when ticked").assertExists()
        compose.onNodeWithContentDescription("Move 2% milk up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move 2% milk down").performClick()
        assertEquals(1 to 1, moved)
        compose.onNodeWithText("flour · 2 kg").performClick()
        compose.onNodeWithText("Name").performTextReplacement("bread flour")
        compose.onNodeWithText("Save").performClick()
        assertEquals(StapleForm(2, "bread flour", "2", "kg", weekly = false), saved)
    }
}
