package dev.mealprep.app.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A provider like Files / Drive: answers the display-name query (the stream is registered on the resolver). */
class NamesProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(uri.getQueryParameter("name"))) }
    override fun getType(uri: Uri): String? = uri.getQueryParameter("type")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
class ImportFilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    init { Robolectric.setupContentProvider(NamesProvider::class.java, "test.files") }

    private fun uri(name: String, type: String = "application/json") =
        Uri.parse("content://test.files/doc/1?name=${Uri.encode(name)}&type=${Uri.encode(type)}")

    /** [size] bytes of "x" without holding them in memory. */
    private fun stream(size: Long) = object : InputStream() {
        var left = size
        override fun read(): Int = if (left-- > 0) 'x'.code else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt(); b.fill('x'.code.toByte(), off, off + n); left -= n; return n
        }
    }

    @Test fun `copies the file into the cache and keeps its display name`() {
        val u = uri("Lentil soup.recipe.json")
        shadowOf(resolver).registerInputStream(u, """{"@type":"Recipe"}""".byteInputStream())
        val dir = tmp.newFolder("imports")
        val r = ImportFiles.copyIn(resolver, u, dir) as CopyResult.Ok
        assertEquals("Lentil soup.recipe.json", r.value.name)
        assertEquals("""{"@type":"Recipe"}""", r.value.file.readText())
        assertEquals(dir, r.value.file.parentFile); assertTrue(r.value.file.name.endsWith(".json"))
    }

    @Test fun `a saved web page keeps html as its extension`() {
        val u = uri("page", "text/html")
        shadowOf(resolver).registerInputStream(u, "<html><script type=\"application/ld+json\">{}</script>".byteInputStream())
        val r = ImportFiles.copyIn(resolver, u, tmp.newFolder("i")) as CopyResult.Ok
        assertTrue(r.value.file.name.endsWith(".html"))
    }

    @Test fun `a file over the cap stops early and leaves nothing behind`() {
        val u = uri("big.json")
        shadowOf(resolver).registerInputStream(u, stream(ImportFiles.MAX_BYTES + 1))
        val dir = tmp.newFolder("imports")
        assertEquals(CopyResult.Err(ImportFiles.TOO_BIG), ImportFiles.copyIn(resolver, u, dir))
        assertTrue(dir.list()!!.isEmpty())
        val exact = uri("exact.json")
        shadowOf(resolver).registerInputStream(exact, stream(1024))
        assertTrue(ImportFiles.copyIn(resolver, exact, dir, max = 1024) is CopyResult.Ok)
    }

    @Test fun `an empty or unreadable file says so`() {
        val empty = uri("empty.json")
        shadowOf(resolver).registerInputStream(empty, ByteArray(0).inputStream())
        val dir = tmp.newFolder("imports")
        assertEquals(CopyResult.Err(ImportFiles.EMPTY), ImportFiles.copyIn(resolver, empty, dir))
        val broken = uri("broken.json")
        shadowOf(resolver).registerInputStream(broken, object : InputStream() { override fun read(): Int = throw SecurityException("grant lapsed") })
        assertEquals(CopyResult.Err(ImportFiles.UNREADABLE), ImportFiles.copyIn(resolver, broken, dir))
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun `old copies are pruned`() {
        val dir = tmp.newFolder("imports")
        val old = java.io.File(dir, "old.json").apply { writeText("{}"); setLastModified(1_000L) }
        val fresh = java.io.File(dir, "fresh.json").apply { writeText("{}"); setLastModified(2L * 24 * 3600 * 1000) }
        ImportFiles.prune(dir, now = 2L * 24 * 3600 * 1000 + 5)
        assertFalse(old.exists()); assertTrue(fresh.exists())
    }

    @Test fun `extension by name, then type`() {
        assertEquals("html", ImportFiles.extension("Soup.HTM", null))
        assertEquals("json", ImportFiles.extension("backup.json", "text/html"))
        assertEquals("html", ImportFiles.extension("download", "text/html"))
        assertEquals("json", ImportFiles.extension("download", "application/octet-stream"))
    }
}
