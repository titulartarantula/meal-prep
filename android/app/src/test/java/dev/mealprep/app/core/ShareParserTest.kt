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
}
