package dev.mealprep.app.ui.exchange

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecipePickerTest {
    private val first = Uri.parse("content://onedrive/first")
    private val second = Uri.parse("content://onedrive/second")
    private val document = Uri.parse("content://onedrive/document")
    private val image: (Uri) -> Boolean = { it == first || it == second }

    @Test fun `all photos retain picker order and go to photo review`() {
        assertEquals(RecipePickerSelection.Photos(listOf(second, first)), recipePickerSelection(listOf(second, first), image))
        assertEquals(RecipePickerSelection.Photos(listOf(first)), recipePickerSelection(listOf(first), image))
    }

    @Test fun `a single document uses the document preview`() {
        assertEquals(RecipePickerSelection.Document(document), recipePickerSelection(listOf(document), image))
    }

    @Test fun `mixed picks and multiple documents give actionable errors instead of dropping files`() {
        assertEquals(RecipePickerSelection.Error(MIXED_FILES), recipePickerSelection(listOf(first, document), image))
        assertEquals(RecipePickerSelection.Error(MANY_DOCUMENTS), recipePickerSelection(listOf(document, Uri.parse("content://onedrive/other")), image))
    }

    @Test fun `too many photos are refused before cloud copies begin`() {
        val uris = (1..11).map { Uri.parse("content://onedrive/photo/$it") }
        assertEquals(RecipePickerSelection.Error(MANY_PHOTOS), recipePickerSelection(uris) { true })
        assertEquals(RecipePickerSelection.Photos(uris.take(10)), recipePickerSelection(uris.take(10)) { true })
        assertTrue(MANY_PHOTOS.contains("10"))
    }

    @Test fun `cancel does nothing and supported types include images`() {
        assertNull(recipePickerSelection(emptyList(), image))
        assertTrue(PICK_TYPES.contains("image/*"))
        assertTrue(PICK_TYPES.contains("application/pdf"))
    }
}
