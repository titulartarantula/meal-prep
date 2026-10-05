package dev.mealprep.app.ui.camera

import android.net.Uri
import dev.mealprep.app.MainDispatcherRule
import dev.mealprep.app.await
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    private fun vm(scale: suspend (java.io.File, java.io.File) -> Unit = { raw, out -> raw.copyTo(out) }) =
        CameraViewModel(PageStore(tmp.root), scale) { uri, out ->
            if (uri.toString().contains("bad")) error("unreadable") else out.writeText(uri.lastPathSegment!!)
        }

    @Test fun `captured pages are scaled, retakes replace, done numbers them`() = runTest {
        val vm = vm()
        fun shoot(text: String) = vm.rawFile().apply { writeText(text) }.also(vm::onCaptured)
        val first = shoot("one")
        vm.state.await { it.pages.size == 1 }
        assertFalse(first.exists())                                // raw capture removed after scaling
        shoot("two"); vm.state.await { it.pages.size == 2 }
        vm.retake(0); shoot("ONE"); vm.state.await { it.pages[0].readText() == "ONE" }
        shoot("three"); vm.state.await { it.pages.size == 3 }
        vm.move(2, -1)
        vm.remove(0)
        val dir = vm.done()
        assertEquals(listOf("three", "two"), PageStore.pagesIn(dir).map { it.readText() })
        assertEquals(2, dir.listFiles()!!.size)                    // replaced and deleted pages are gone
    }

    @Test fun `a page that can't be saved says so and adds nothing`() = runTest {
        val vm = vm { _, _ -> error("decoder failed") }
        val raw = vm.rawFile().apply { writeText("x") }
        vm.onCaptured(raw)
        vm.busy.await { !it }
        assertEquals(CameraViewModel.SAVE_FAILED, vm.error.value)
        assertTrue(vm.state.value.pages.isEmpty())
        assertFalse(raw.exists())
    }

    @Test fun `picked photos are added in order, unreadable ones reported, never more than ten`() = runTest {
        val vm = vm()
        vm.onPicked(listOf(Uri.parse("content://m/p1"), Uri.parse("content://m/bad"), Uri.parse("content://m/p2")))
        vm.busy.await { !it }
        assertEquals(listOf("p1", "p2"), vm.state.value.pages.map { it.readText() })
        assertEquals(CameraViewModel.PICK_FAILED, vm.error.value)
        vm.onPicked((3..12).map { Uri.parse("content://m/p$it") })
        vm.busy.await { !it }
        assertEquals(10, vm.state.value.pages.size)
        assertEquals("p10", vm.state.value.pages.last().readText())
        assertEquals(CameraViewModel.ONLY_TEN, vm.error.value)
    }

    @Test fun `camera error is reported and the raw file removed`() {
        val vm = vm()
        val raw = vm.rawFile().apply { writeText("x") }
        vm.onCaptureFailed(raw)
        assertEquals(CameraViewModel.CAMERA_FAILED, vm.error.value)
        assertFalse(raw.exists())
        assertNull(vm.state.value.retaking)
    }
}
