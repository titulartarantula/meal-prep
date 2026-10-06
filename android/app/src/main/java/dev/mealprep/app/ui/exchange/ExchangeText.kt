package dev.mealprep.app.ui.exchange

import dev.mealprep.app.data.api.ApiError
import dev.mealprep.app.data.api.userMessage

/**
 * What the import and export screens say when something goes wrong: every error the server can send for a file
 * becomes a plain sentence that says what to do next (DESIGN.md "Contracts for the app"); never an exception's text.
 */
object ExchangeText {
    const val OFFLINE_IMPORT = "Importing needs the home network (or WireGuard). Connect, then try again."
    const val OFFLINE_EXPORT = "Exporting needs the home network (or WireGuard). Connect, then try again."
    const val NO_RECIPES = "There are no recipes in this file. Choose a recipe file exported from Meal Prep or another " +
        "recipe app, or a recipe's web page saved as .html."
    const val NOT_A_RECIPE_FILE = "Meal Prep can't import this kind of file. Choose a recipe file (.json) or a recipe's " +
        "web page saved as .html."
    const val NOT_JSON = "This recipe file is damaged or incomplete. Export it again from the other app, then import the new file."
    const val TOO_DEEP = "This file is built in a way Meal Prep can't read. Export it again from the other app, or save " +
        "the recipe's web page and import that instead."
    const val COULDNT_READ = "Meal Prep couldn't read this file. Export it again, or choose another file."
    const val BAD_CHOICES = "Your choices couldn't be sent. Go back, open the file again and choose again."
    const val SLOW_PREVIEW = "The server is taking too long to read this file. Try again in a minute."
    const val SLOW_APPLY = "The server didn't answer in time. Tap the button again: recipes already added won't be added twice."
    const val SLOW_EXPORT = "The server is taking too long to make the file. Try again in a minute."
    const val GONE = "The server doesn't know this import any more. Check Recipes to see what was added."
    const val RECIPE_GONE = "That recipe isn't in the library any more."
    const val SERVER_PROBLEM = "The server had a problem. Try again in a minute."

    /** "This file is too big to import (max 20 MB)." + what to do (the cap is the server's, so its number is kept). */
    fun tooBig(detail: String?): String {
        val mb = detail?.let { Regex("""max (\d+) MB""").find(it)?.groupValues?.get(1) } ?: "20"
        return "This file is too big to import (max $mb MB). Export fewer recipes at a time, then import each file."
    }

    // The server's 422 details (server/mealprep/exchange/importer.py, safe.py, api.py) → our words.
    private val SERVER_422 = mapOf(
        "No recipes found in this file." to NO_RECIPES,
        "This file can't be imported. Choose a recipe file (.json) or a saved web page (.html)." to NOT_A_RECIPE_FILE,
        "This file isn't valid JSON." to NOT_JSON,
        "This file is nested too deeply to read." to TOO_DEEP,
        "Couldn't read this file." to COULDNT_READ,
    )

    /** Reading a file (the preview) or starting the import ([apply]); [job]: asking for a running import. */
    fun importError(e: ApiError, apply: Boolean = false, job: Boolean = false): String = when (e) {
        ApiError.Unreachable -> OFFLINE_IMPORT
        ApiError.TimedOut -> if (apply) SLOW_APPLY else SLOW_PREVIEW
        is ApiError.Http -> when {
            e.code == 413 -> tooBig(e.detail)
            e.code == 404 && job -> GONE
            e.code == 422 && e.detail?.startsWith("choices") == true -> BAD_CHOICES
            e.code == 422 -> SERVER_422[e.detail] ?: COULDNT_READ
            e.code >= 500 -> SERVER_PROBLEM
            else -> COULDNT_READ
        }
        else -> e.userMessage()   // not configured, the token, a reply the app doesn't understand: already plain
    }

    fun exportError(e: ApiError): String = when (e) {
        ApiError.Unreachable -> OFFLINE_EXPORT
        ApiError.TimedOut -> SLOW_EXPORT
        is ApiError.Http -> if (e.code == 404) RECIPE_GONE else SERVER_PROBLEM
        else -> e.userMessage()
    }
}
