package dev.mealprep.app.core

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ExportFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `file name from Content-Disposition`() {
        assertEquals("meal-prep-recipes-2026-10-06.json", ExportFiles.fileName("attachment; filename=\"meal-prep-recipes-2026-10-06.json\""))
        assertEquals("soup.recipe.json", ExportFiles.fileName("attachment; filename=soup.recipe.json"))
        assertEquals("crème.recipe.json", ExportFiles.fileName("attachment; filename=\"x.json\"; filename*=UTF-8''cr%C3%A8me.recipe.json"))
        assertEquals("a-b.json", ExportFiles.fileName("attachment; filename=\"../a/b.json\""))
        assertNull(ExportFiles.fileName(null))
        assertNull(ExportFiles.fileName("attachment"))
        assertNull(ExportFiles.fileName("attachment; filename=\"\""))
    }

    @Test fun `save writes the whole file under its name and prunes old exports`() {
        val dir = tmp.newFolder("exports")
        val old = java.io.File(dir, "old.json").apply { writeText("x"); setLastModified(1_000L) }
        val f = ExportFiles.save("""{"name":"Test"}""".byteInputStream(), dir, "test.recipe.json", now = 3L * 24 * 3600 * 1000)
        assertEquals("test.recipe.json", f.name); assertEquals("""{"name":"Test"}""", f.readText())
        assertFalse(old.exists())
        assertEquals(listOf("test.recipe.json"), dir.list()!!.toList())   // no .part left behind
        ExportFiles.save("again".byteInputStream(), dir, "test.recipe.json")
        assertEquals("again", f.readText())                                  // replaced, not appended
    }

    @Test fun `save to a file copies the bytes into the chosen document`() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        val uri = Uri.parse("content://com.example.documents/document/42")
        val sink = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(uri, sink)
        val f = tmp.newFile("e.json").apply { writeText("""{"@graph":[]}""") }
        ExportFiles.copyTo(resolver, f, uri)
        assertArrayEquals(f.readBytes(), sink.toByteArray())
    }

    @Test fun `send builds a chooser with the stream and a read grant`() {
        val uri = Uri.parse("content://dev.mealprep.app.pages/exports/soup.recipe.json")
        val chooser = ExportFiles.sendIntent(uri, "soup.recipe.json", "Send this recipe")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Send this recipe", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("application/json", send.type)
        assertEquals(uri, send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }
}
