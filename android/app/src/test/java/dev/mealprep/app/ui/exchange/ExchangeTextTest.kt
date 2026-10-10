package dev.mealprep.app.ui.exchange

import dev.mealprep.app.data.api.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every error the server documents for import / export becomes our plain sentence (DESIGN.md contracts). */
class ExchangeTextTest {
    private fun e422(d: String) = ApiError.Http(422, d)

    @Test fun `every documented 422 detail is mapped`() {
        assertEquals(ExchangeText.NO_RECIPES, ExchangeText.importError(e422("No recipes found in this file.")))
        assertEquals(ExchangeText.NOT_A_RECIPE_FILE, ExchangeText.importError(
            e422("This file can't be imported. Choose a recipe file (.json) or a saved web page (.html).")))
        assertEquals(ExchangeText.NOT_JSON, ExchangeText.importError(e422("This file isn't valid JSON.")))
        assertEquals(ExchangeText.TOO_DEEP, ExchangeText.importError(e422("This file is nested too deeply to read.")))
        assertEquals(ExchangeText.COULDNT_READ, ExchangeText.importError(e422("Couldn't read this file.")))
        assertEquals(ExchangeText.BAD_CHOICES, ExchangeText.importError(
            e422("choices must be a JSON object of item key → add / skip / update"), apply = true))
        assertEquals(ExchangeText.COULDNT_READ, ExchangeText.importError(e422("Field required")))   // FastAPI's own
        assertEquals(ExchangeText.COULDNT_READ, ExchangeText.importError(ApiError.Http(422, null)))
    }

    @Test fun `document errors are mapped`() {
        mapOf(
            "This file can't be imported. Choose a recipe document (PDF, Word or text), a recipe file (.json) or a saved web page (.html)." to ExchangeText.NOT_A_RECIPE_FILE,
            "This PDF is locked with a password. Save a copy without the password and import that." to ExchangeText.LOCKED,
            "This is an old Word file (.doc). Save it as .docx or PDF, then import that." to ExchangeText.OLD_WORD,
            "This looks like a photo. To add a recipe from photos, use Add recipe → Choose photos." to ExchangeText.PHOTO,
            "There's no recipe text in this document." to ExchangeText.NO_TEXT,
            "Couldn't read this document. It may be damaged: save or export it again, then import the new copy." to ExchangeText.DAMAGED,
            "This document is too big to read in one go. Split it into smaller files and import each one." to ExchangeText.DOC_TOO_BIG,
            "Read this document again before adding its recipes." to ExchangeText.READ_AGAIN,
        ).forEach { (server, ours) -> assertEquals(server, ours, ExchangeText.importError(e422(server))) }
        assertTrue(ExchangeText.NOT_A_RECIPE_FILE.contains("PDF, Word or text"))
    }

    @Test fun `a document's failed read in our words`() {
        assertEquals(ExchangeText.NO_DOC_RECIPES, ExchangeText.readError("No recipes found in this document."))
        assertEquals(ExchangeText.AI_DOWN, ExchangeText.readError("The AI couldn't read this document. Try again in a few minutes."))
        assertEquals(ExchangeText.READ_RESTARTED, ExchangeText.readError("interrupted by a server restart"))
        assertEquals(ExchangeText.COULDNT_READ, ExchangeText.readError("RuntimeError: bug"))
        assertEquals(ExchangeText.COULDNT_READ, ExchangeText.readError(null))
    }

    @Test fun `413 keeps the server's cap`() {
        assertEquals("This file is too big to import (max 20 MB). Export fewer recipes at a time, then import each file.",
            ExchangeText.importError(ApiError.Http(413, "This file is too big to import (max 20 MB).")))
        assertTrue(ExchangeText.importError(ApiError.Http(413, "This file is too big to import (max 5 MB).")).contains("max 5 MB"))
        assertTrue(ExchangeText.importError(ApiError.Http(413, null)).contains("max 20 MB"))
    }

    @Test fun `offline, slow, token, gone and server trouble`() {
        assertEquals(ExchangeText.OFFLINE_IMPORT, ExchangeText.importError(ApiError.Unreachable))
        assertEquals(ExchangeText.SLOW_PREVIEW, ExchangeText.importError(ApiError.TimedOut))
        assertEquals(ExchangeText.SLOW_APPLY, ExchangeText.importError(ApiError.TimedOut, apply = true))
        assertEquals("The server rejected the token. Check it in Settings.", ExchangeText.importError(ApiError.Unauthorized))
        assertEquals("Set the server address and token in Settings.", ExchangeText.importError(ApiError.NotConfigured))
        assertEquals(ExchangeText.GONE, ExchangeText.importError(ApiError.Http(404, "import 7"), job = true))
        assertEquals(ExchangeText.SERVER_PROBLEM, ExchangeText.importError(ApiError.Http(500, "Internal Server Error")))
        assertEquals(ExchangeText.OFFLINE_EXPORT, ExchangeText.exportError(ApiError.Unreachable))
        assertEquals(ExchangeText.SLOW_EXPORT, ExchangeText.exportError(ApiError.TimedOut))
        assertEquals(ExchangeText.RECIPE_GONE, ExchangeText.exportError(ApiError.Http(404, "recipe 4")))
        assertEquals(ExchangeText.SERVER_PROBLEM, ExchangeText.exportError(ApiError.Http(502, "x")))
    }

    @Test fun `no message shows internals`() {
        val all = listOf(422 to "choices must be a JSON object of item key → add / skip / update", 404 to "import 7",
            500 to "Traceback", 422 to "Field required").map { (c, d) -> ExchangeText.importError(ApiError.Http(c, d)) }
        all.forEach { m -> assertFalse(m, m.contains("choices must") || m.contains("import 7") || m.contains("Traceback") || m.contains("Field required")) }
    }
}
