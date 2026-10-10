package dev.mealprep.app.core

import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ShareParserTest {
    private fun text(t: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)

    @Test fun `NYT app share text gives the clean recipe url`() {
        val t = "Best Chocolate Chip Cookies https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies?smid=ck-recipe-iOS-share"
        assertEquals(ShareInput.NytLink("https://cooking.nytimes.com/recipes/1015819-chocolate-chip-cookies", t),
            ShareParser.parse(text(t)))
    }

    @Test fun `www and http are normalized like the server`() {
        assertEquals("https://cooking.nytimes.com/recipes/12-x", ShareParser.nytUrl("http://www.cooking.nytimes.com/recipes/12-x"))
    }

    @Test fun `news link and plain text are not recipes`() {
        val news = "https://www.nytimes.com/2026/10/01/dining/fall-soups.html"
        assertEquals(ShareInput.NotARecipe(news), ShareParser.parse(text(news)))
        assertEquals(ShareInput.NotARecipe("make soup"), ShareParser.parse(text("make soup")))
        assertNull(ShareParser.nytUrl("https://cooking.nytimes.com/"))
    }

    @Test fun `single and multiple images`() {
        val a = Uri.parse("content://media/1"); val b = Uri.parse("content://media/2")
        val one = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, a)
        assertEquals(ShareInput.Photos(listOf(a)), ShareParser.parse(one))
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
        assertEquals(ShareInput.Photos(listOf(a, b)), ShareParser.parse(many))
    }

    @Test fun `launcher intent is not a share`() {
        assertNull(ShareParser.parse(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun `styled share text (a Spanned extra) still finds the recipe`() {
        val t = SpannableString("Chili https://cooking.nytimes.com/recipes/1015819-chili")
        t.setSpan(StyleSpan(Typeface.BOLD), 0, 5, 0)
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t as CharSequence)
        assertEquals(ShareInput.NytLink("https://cooking.nytimes.com/recipes/1015819-chili", t.toString()), ShareParser.parse(i))
    }

    @Test fun `recipe files shared from another app`() {
        val u = Uri.parse("content://com.example.files/doc/7")
        listOf("application/json", "application/ld+json", "text/html", "application/json; charset=utf-8").forEach { t ->
            val i = Intent(Intent.ACTION_SEND).setType(t).putExtra(Intent.EXTRA_STREAM, u)
            assertEquals(ShareInput.RecipeFile(u, t), ShareParser.parse(i))
        }
        // A file manager that shares a .json as text/plain with only the stream.
        val plain = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u)
        assertEquals(ShareInput.RecipeFile(u, "text/plain"), ShareParser.parse(plain))
        // A text share that also carries a stream stays a text share.
        val both = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u)
            .putExtra(Intent.EXTRA_TEXT, "https://cooking.nytimes.com/recipes/12-x")
        assertEquals(ShareInput.NytLink("https://cooking.nytimes.com/recipes/12-x", "https://cooking.nytimes.com/recipes/12-x"), ShareParser.parse(both))
    }

    @Test fun `open with from Files is a recipe file`() {
        val u = Uri.parse("content://com.example.files/doc/backup.json")
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/json")
        assertEquals(ShareInput.RecipeFile(u, "application/json"), ShareParser.parse(view))
        assertNull(ShareParser.parse(Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://example.org/x.json"))))
    }

    @Test fun `zip and other binaries are not recipe file types`() {
        assertEquals(false, ShareParser.isRecipeFileType("application/zip"))
        assertEquals(false, ShareParser.isRecipeFileType("application/octet-stream"))
        assertEquals(false, ShareParser.isRecipeFileType(null))
        assertEquals(true, ShareParser.isRecipeFileType("Application/LD+JSON"))
    }

    @Test fun `a shared web link is told apart from plain text`() {
        assertEquals(true, ShareParser.hasLink("Soup https://recipes.example.org/soup"))
        assertEquals(false, ShareParser.hasLink("make soup"))
    }

    @Test fun `recipe documents shared from another app or opened from Files`() {
        val u = Uri.parse("content://com.example.files/doc/9")
        listOf("application/pdf", ShareParser.DOCX).forEach { t ->
            assertEquals(ShareInput.RecipeFile(u, t), ShareParser.parse(Intent(Intent.ACTION_SEND).setType(t).putExtra(Intent.EXTRA_STREAM, u)))
            assertEquals(ShareInput.RecipeFile(u, t), ShareParser.parse(Intent(Intent.ACTION_VIEW).setDataAndType(u, t)))
        }
        assertEquals(ShareInput.RecipeFile(u, "text/plain"), ShareParser.parse(Intent(Intent.ACTION_VIEW).setDataAndType(u, "text/plain")))
        // A text file shared with its name as the text is still the file.
        val named = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, u)
            .putExtra(Intent.EXTRA_TEXT, "Summer salads.txt")
        assertEquals(ShareInput.RecipeFile(u, "text/plain"), ShareParser.parse(named))
        // Old Word files aren't offered (the server can't read them).
        assertEquals(false, ShareParser.isRecipeFileType("application/msword"))
    }
}
