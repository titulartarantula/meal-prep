package dev.mealprep.app.core

import android.content.Intent
import android.net.Uri
import java.io.File

sealed interface ShareInput {
    data class NytLink(val url: String, val text: String) : ShareInput
    /** Images shared from another app — content URIs, only readable while the receiving activity lives. */
    data class Photos(val uris: List<Uri>) : ShareInput
    /** Pages already copied into the app's own storage (in-app camera, or Photos after copying). */
    data class Pages(val dir: File) : ShareInput
    data class NotARecipe(val text: String) : ShareInput
}

object ShareParser {
    // Same pattern as the server (mealprep/importers/nyt.py _URL), so the app never sends what the server rejects.
    private val NYT = Regex("""https?://(?:www\.)?cooking\.nytimes\.com/recipes/[\w-]+""")

    fun nytUrl(text: String?): String? =
        text?.let { NYT.find(it)?.value }?.replace("://www.", "://")?.replace("http://", "https://")

    fun parse(intent: Intent): ShareInput? = when (intent.action) {
        Intent.ACTION_SEND ->
            if (intent.type?.startsWith("image/") == true) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { ShareInput.Photos(listOf(it)) }
            } else {
                // CharSequence, not String: senders may share styled (Spanned) text, which getStringExtra returns as null.
                val text = listOfNotNull(
                    intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
                    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                ).distinct().joinToString("\n")
                nytUrl(text)?.let { ShareInput.NytLink(it, text) } ?: ShareInput.NotARecipe(text)
            }
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { ShareInput.Photos(it.toList()) }
        else -> null
    }
}
