package dev.mealprep.app.ui.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `commit renames pages in the chosen order and drops the rest`() {
        val store = PageStore(tmp.root)
        val dir = store.newBatch()
        val (a, b, c, gone) = List(4) { i -> store.newFile(dir).apply { writeText("p$i") } }
        File(dir, "raw-1.jpg").writeText("raw")                    // a capture that was never scaled
        val out = store.commitOrder(dir, listOf(c, a, b))
        assertEquals(listOf("page01.jpg", "page02.jpg", "page03.jpg"), out.map { it.name })
        assertEquals(listOf("p2", "p0", "p1"), PageStore.pagesIn(dir).map { it.readText() })
        assertFalse(gone.exists())
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test fun `committing again in a new order (share screen after the camera) swaps without clobbering`() {
        val store = PageStore(tmp.root)
        val dir = store.newBatch()
        val first = store.commitOrder(dir, List(3) { i -> store.newFile(dir).apply { writeText("p$i") } })
        store.commitOrder(dir, listOf(first[2], first[0], first[1]))
        assertEquals(listOf("p2", "p0", "p1"), PageStore.pagesIn(dir).map { it.readText() })
    }

    @Test fun `old batches are pruned, recent ones kept`() {
        val store = PageStore(tmp.root)
        val old = store.newBatch().apply { setLastModified(1_000) }
        val recent = store.newBatch()
        store.pruneOlderThan(System.currentTimeMillis() - 60_000)
        assertFalse(old.exists()); assertTrue(recent.exists())
        store.discard(recent)
        assertFalse(recent.exists())
    }
}
