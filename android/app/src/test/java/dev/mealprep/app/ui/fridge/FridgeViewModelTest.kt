package dev.mealprep.app.ui.fridge

import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.TestEnv
import dev.mealprep.app.await
import dev.mealprep.app.fixture
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FridgeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val env = TestEnv()
    private val today = LocalDate.parse("2026-10-07")

    @Before fun setUp() {
        env.on("GET", "/fridge", body = fixture("fridge.json"))
        env.on("GET", "/fridge/reads", body = """{"read":null}""")
    }
    @After fun tearDown() = env.close()

    private suspend fun loaded(debounce: Long = 0) =
        FridgeViewModel(env.repo, { today }, CoroutineScope(Dispatchers.Default), debounce).also { vm -> vm.state.await { !it.loading } }

    @Test fun `loads the list and an open read`() = runTest {
        env.on("GET", "/fridge/reads", body = """{"read":${fixture("fridge_read_ready.json")}}""")
        val vm = loaded()
        assertEquals(listOf("Cilantro", "Eggs", "Spinach", "Yogurt"), vm.state.value.items.map { it.name })
        vm.state.await { it.read != null }
        assertEquals(12, vm.state.value.read!!.id)
    }

    @Test fun `a picked suggestion is sent as from the list, a typed name is not`() = runTest {
        env.on("POST", "/fridge", code = 201, body = """{"id":9,"name":"kale","amount_as_of":"2026-10-07"}""")
        val vm = loaded()
        assertTrue(vm.save(FridgeForm(name = " whole milk ", amount = "1", unit = "l", fromList = true)))
        assertEquals("""{"name":"whole milk","qty":1.0,"unit":"l","use_soon":false,"from_list":true}""", env.awaitBody("POST", "/fridge"))
        vm.state.await { !it.busy }
        assertTrue(vm.save(FridgeForm(name = "Kale", useSoon = true, useBy = LocalDate.parse("2026-10-10"))))
        env.state(vm) { env.bodies("POST", "/fridge").size == 2 }
        assertEquals("""{"name":"Kale","use_soon":true,"use_by":"2026-10-10","from_list":false}""", env.bodies("POST", "/fridge").last())
        assertTrue(env.count("GET", "/fridge") >= 2)   // read again after each write
    }

    @Test fun `a duplicate offers the item that is there`() = runTest {
        env.on("POST", "/fridge", code = 409, body = """{"detail":"Already in the fridge: 6 Eggs","id":1}""")
        val vm = loaded()
        vm.save(FridgeForm(name = "eggs", amount = "12"))
        val s = vm.state.await { it.exists != null }
        assertEquals("Already in the fridge: 6 Eggs", s.exists!!.detail); assertEquals(1, s.exists.id); assertNull(s.message)
        vm.clearMessage()
        assertNull(vm.state.value.exists)
    }

    @Test fun `bad input is caught before sending`() = runTest {
        val vm = loaded()
        assertFalse(vm.save(FridgeForm(name = "  ")))
        assertEquals("Give it a name, like eggs or spinach.", vm.state.value.message)
        assertFalse(vm.save(FridgeForm(name = "eggs", amount = "lots")))
        assertFalse(vm.save(FridgeForm(name = "x".repeat(81))))
        assertEquals(0, env.count("POST", "/fridge"))
    }

    @Test fun `offline the saved copy shows and writes are refused politely`() = runTest {
        loaded()
        env.offline = true
        val vm = loaded()
        assertNotNull(vm.state.value.offlineSince); assertEquals(4, vm.state.value.items.size)
        vm.save(FridgeForm(name = "kale"))
        assertEquals(FridgeLogic.OFFLINE, vm.state.await { it.message != null }.message)
    }

    @Test fun `remove waits for the snackbar, undo keeps it, commit deletes it`() = runTest {
        env.on("DELETE", "/fridge/1", code = 204)
        val vm = loaded()
        val eggs = vm.state.value.items.first { it.id == 1 }
        vm.remove(eggs)
        assertFalse(vm.state.value.shown.any { it.id == 1 })
        vm.undoRemove()
        assertTrue(vm.state.value.shown.any { it.id == 1 }); assertEquals(0, env.count("DELETE", "/fridge/1"))
        vm.remove(eggs)
        vm.commitRemove()
        env.awaitBody("DELETE", "/fridge/1")
        assertEquals(1, env.count("DELETE", "/fridge/1"))
    }

    @Test fun `still right and names as you type`() = runTest {
        env.on("PATCH", "/fridge/2", body = """{"id":2,"name":"Cilantro","amount_as_of":"2026-10-07"}""")
        env.on("GET", "/fridge/names", body = """["egg","eggplant"]""")
        val vm = loaded()
        vm.confirm(2)
        assertEquals("""{"confirm":true}""", env.awaitBody("PATCH", "/fridge/2"))
        vm.typed("eg")
        assertEquals(listOf("egg", "eggplant"), vm.state.await { it.names.isNotEmpty() }.names)
        vm.typed(" ")
        assertEquals(emptyList<String>(), vm.state.await { it.names.isEmpty() }.names)
    }
}

/** Waits (real time) until [cond] holds. */
private suspend fun TestEnv.state(vm: FridgeViewModel, cond: () -> Boolean) {
    kotlinx.coroutines.withContext(Dispatchers.Default) { kotlinx.coroutines.withTimeout(5_000) { while (!cond()) kotlinx.coroutines.delay(10) } }
    vm.state.await { !it.busy }
}
