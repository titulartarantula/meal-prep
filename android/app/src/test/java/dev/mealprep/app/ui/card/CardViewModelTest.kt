package dev.mealprep.app.ui.card

import androidx.lifecycle.viewModelScope
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.fixture
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CardViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    private val vms = mutableListOf<CardViewModel>()
    @After fun tearDown() {
        runBlocking { withTimeout(5_000) { vms.forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() } } }
        env.close()
    }

    private fun vm(id: Int) = CardViewModel(env.repo, id).also { vms += it }

    @Test fun `card loads and stays readable offline`() = runTest {
        env.on("GET", "/plan/21/card", body = fixture("cook_card.json"))
        assertEquals(3, vm(21).state.await { !it.loading }.card!!.steps.size)
        env.offline = true
        val s = vm(21).state.await { !it.loading }
        assertNotNull(s.card); assertNotNull(s.offlineSince); assertNull(s.message)
    }

    @Test fun `no card yet explains why`() = runTest {
        val s = vm(99).state.await { !it.loading }
        assertEquals(CardViewModel.NO_CARD, s.message)
    }

    @Test fun `never saved and off the home network says so`() = runTest {
        env.offline = true
        val s = vm(21).state.await { !it.loading }
        assertNull(s.card)
        assertTrue(s.message!!.contains("home Wi-Fi"))
    }

    @Test fun `a failed refresh keeps the card on screen`() = runTest {
        env.on("GET", "/plan/21/card", body = fixture("cook_card.json"))
        val vm = vm(21)
        vm.state.await { it.card != null }
        env.on("GET", "/plan/21/card", code = 500, body = """{"detail":"boom"}""")
        vm.reload()
        val s = vm.state.await { it.message != null }
        assertEquals("Chili", s.card!!.title)
    }

    @Test fun `a note rated after the plan was written shows on the card`() = runTest {
        env.on("GET", "/plan/21/card", body = fixture("cook_card.json"))
        env.on("GET", "/recipes/5", body = fixture("recipe_3.json").replace("\"notes\":[]",
            "\"notes\": [{\"note\": \"double the beans\", \"date\": \"2026-10-06\", \"rated_at\": \"2026-10-12T08:00:00+00:00\"}]"))
        val s = vm(21).state.await { it.card?.ratingNotes?.size == 2 }
        assertEquals(listOf("Oct 6: double the beans", "last time: less salt"), s.card!!.ratingNotes)
    }
}
