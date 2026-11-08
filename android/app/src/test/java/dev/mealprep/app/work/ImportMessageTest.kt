package dev.mealprep.app.work

import dev.mealprep.app.data.api.ApiError
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportMessageTest {
    @Test fun `a link the server couldn't read says why`() {
        val blocked = "kitchen.example.com refused to show that page to the Meal Prep server (it blocks automated readers). " +
            "Save the page from your browser and import the file instead."
        assertEquals(blocked, ImportWorker.importMessage(ApiError.Http(502, blocked), ImportWorker.LINK))
        // the older technical wording, or nothing, stays the plain sentence
        assertEquals("Couldn't read that recipe. Try again later.",
            ImportWorker.importMessage(ApiError.Http(502, "import failed: NytFetchError: x"), ImportWorker.LINK))
        assertEquals("Couldn't read that recipe. Try again later.", ImportWorker.importMessage(ApiError.Http(502, null), ImportWorker.LINK))
        assertEquals(ImportWorker.PHOTO_UNREADABLE, ImportWorker.importMessage(ApiError.Http(502, blocked), ImportWorker.PHOTO))
    }
}
