package dev.mealprep.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagesStateTest {
    private val a = File("a"); private val b = File("b"); private val c = File("c"); private val x = File("x")

    @Test fun `add, reorder, retake, remove`() {
        var s = PagesState().add(a).add(b).add(c)
        assertEquals(listOf(a, b, c), s.pages)
        s = s.move(2, -1)
        assertEquals(listOf(a, c, b), s.pages)
        assertEquals(s, s.move(0, -1))                               // can't move past either end
        assertEquals(s, s.move(2, 1))
        s = s.retake(1).add(x)
        assertEquals(listOf(a, x, b), s.pages)
        assertNull(s.retaking)
        assertEquals(listOf(a, b), s.remove(1).pages)
    }

    @Test fun `retake can be cancelled and ignores a page that isn't there`() {
        val s = PagesState().add(a).add(b)
        assertEquals(listOf(a, b, c), s.retake(1).cancelRetake().add(c).pages)
        assertNull(s.retake(5).retaking)
        assertNull(s.retake(0).remove(1).retaking)                 // deleting a page ends a retake
    }

    @Test fun `ten pages max`() {
        val ten = (1..10).fold(PagesState()) { s, i -> s.add(File("$i")) }
        assertFalse(ten.canShoot); assertFalse(ten.tooMany)
        assertTrue(ten.retake(3).canShoot)                          // a retake replaces, so it's allowed at ten
        assertEquals(10, ten.retake(3).add(x).pages.size)
        assertTrue(ten.add(File("11")).tooMany)                      // only a gallery share can get here
        assertTrue(PagesState().canShoot)
    }

    @Test fun `shoot button says what will happen`() {
        assertEquals("Snap page", shootLabel(PagesState(), busy = false))
        assertEquals("Add page", shootLabel(PagesState().add(a), busy = false))
        assertEquals("Retake page 2", shootLabel(PagesState().add(a).add(b).retake(1), busy = false))
        assertEquals("Saving…", shootLabel(PagesState().add(a), busy = true))
    }
}
