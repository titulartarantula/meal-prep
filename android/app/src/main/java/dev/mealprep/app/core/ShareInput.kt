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
    /** A recipe file (a recipe document: PDF, Word, text; schema.org JSON-LD; a saved web page) shared from another
     *  app or opened from Files: a content URI readable only while the receiving activity lives, so it is copied in at
     *  once (ImportFiles). */
    data class RecipeFile(val uri: Uri, val mime: String?) : ShareInput
}

object ShareParser {
    // Same pattern as the server (mealprep/importers/nyt.py _URL), so the app never sends what the server rejects.
    private val NYT = Regex("""https?://(?:www\.)?cooking\.nytimes\.com/recipes/[\w-]+""")

    fun nytUrl(text: String?): String? =
        text?.let { NYT.find(it)?.value }?.replace("://www.", "://")?.replace("http://", "https://")

    const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    /** The types of the manifest's recipe-file filters (no zip: Paprika isn't imported; no .doc: the server can't read
     *  old Word files). text/plain: a text document shared as a file. */
    val RECIPE_FILE_TYPES = setOf("application/json", "application/ld+json", "text/html", "application/pdf", DOCX, "text/plain")
    fun isRecipeFileType(type: String?) = type?.substringBefore(';')?.trim()?.lowercase() in RECIPE_FILE_TYPES

    private val LINK = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    /** A shared text with a web link in it (not an NYT Cooking recipe): only a file can bring that recipe in. */
    fun hasLink(text: String) = LINK.containsMatchIn(text)

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
                val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                when {
                    // A file: a recipe type, or a share that carries only a file (some file managers). A shared NYT link
                    // that happens to come with a file (a text/plain share with both) stays a link.
                    stream != null && (isRecipeFileType(intent.type) || intent.getCharSequenceExtra(Intent.EXTRA_TEXT) == null) &&
                        nytUrl(text) == null -> ShareInput.RecipeFile(stream, intent.type)
                    else -> nytUrl(text)?.let { ShareInput.NytLink(it, text) } ?: ShareInput.NotARecipe(text)
                }
            }
        // "Open with" from Files / Drive (the manifest's VIEW filter: content URIs of the recipe file types).
        Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" }?.let { ShareInput.RecipeFile(it, intent.type) }
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?.takeIf { it.isNotEmpty() }?.let { ShareInput.Photos(it.toList()) }
        else -> null
    }
}
