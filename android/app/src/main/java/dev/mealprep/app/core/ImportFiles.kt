package dev.mealprep.app.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.UUID

/** A recipe file copied into the app's cache ([file]), with the name it had where it came from ([name]). */
data class ImportFile(val file: File, val name: String)

sealed interface CopyResult {
    data class Ok(val value: ImportFile) : CopyResult
    data class Err(val message: String) : CopyResult
}

/**
 * Recipe files (documents, JSON-LD, saved pages) picked in the system picker or shared from another app arrive as content:// URIs whose read grant
 * lasts only while the receiving screen lives: they are copied into cacheDir/imports at once (capped), and only the
 * copy is ever read again (preview, then apply).
 */
object ImportFiles {
    const val DIR = "imports"
    /** The server's cap (MEALPREP_IMPORT_MAX_MB, default 20). */
    const val MAX_BYTES = 20L * 1024 * 1024
    private const val KEPT_MS = 24L * 3600 * 1000

    const val TOO_BIG = "This file is too big to import (max 20 MB). Export fewer recipes at a time, then import each file."
    const val UNREADABLE = "Couldn't open that file. Choose it again, or save it to your phone first and import it from there."
    const val EMPTY = "That file is empty. Choose another file."

    private class TooBig : IOException()

    /** Whether a picked file is a photo, by the type its provider gives (a provider that fails to answer: not one). */
    fun isPhoto(resolver: ContentResolver, uri: Uri): Boolean =
        isPhoto(runCatching { resolver.getType(uri) }.getOrNull(), displayName(resolver, uri))

    /** Some cloud providers label an image as octet-stream; use its displayed extension only in that case. */
    fun isPhoto(mime: String?, name: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.startsWith("image/")) return true
        if (type.isNotEmpty() && type != "application/octet-stream") return false
        return name?.lowercase()?.let { n ->
            listOf(".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif", ".gif", ".bmp", ".tif", ".tiff", ".avif")
                .any(n::endsWith)
        } == true
    }

    fun copyIn(resolver: ContentResolver, uri: Uri, dir: File, max: Long = MAX_BYTES, now: Long = System.currentTimeMillis()): CopyResult {
        val name = displayName(resolver, uri) ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "recipes"
        val ext = extension(name, runCatching { resolver.getType(uri) }.getOrNull())
        dir.mkdirs()
        prune(dir, now)
        val out = File(dir, "${UUID.randomUUID()}.$ext")
        return try {
            val input = resolver.openInputStream(uri) ?: return CopyResult.Err(UNREADABLE)
            input.use { i ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = i.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > max) throw TooBig()
                        o.write(buf, 0, n)
                    }
                }
            }
            if (out.length() == 0L) { out.delete(); CopyResult.Err(EMPTY) } else CopyResult.Ok(ImportFile(out, name))
        } catch (e: TooBig) {
            out.delete(); CopyResult.Err(TOO_BIG)
        } catch (e: Exception) {   // a lapsed grant (SecurityException), a provider that went away, a full disk
            out.delete(); CopyResult.Err(UNREADABLE)
        }
    }

    /** The name the file has in Files / Drive (OpenableColumns.DISPLAY_NAME), if the provider says. */
    fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()?.trim()?.ifBlank { null }

    /** The copy's extension: pdf / docx / doc / txt / html / json, by the name, else the type, else "json" (the server
     *  goes by the content; this only names the copy). */
    fun extension(name: String, mime: String?): String {
        val n = name.lowercase()
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when {
            n.endsWith(".html") || n.endsWith(".htm") -> "html"
            n.endsWith(".json") -> "json"
            n.endsWith(".pdf") -> "pdf"
            n.endsWith(".docx") -> "docx"
            n.endsWith(".doc") -> "doc"
            n.endsWith(".txt") || n.endsWith(".text") || n.endsWith(".md") -> "txt"
            m.contains("html") -> "html"
            m == "application/pdf" -> "pdf"
            m == ShareParser.DOCX -> "docx"
            m == "application/msword" -> "doc"
            m == "text/plain" -> "txt"
            else -> "json"
        }
    }

    /** Copies older than a day (an import nobody finished). */
    fun prune(dir: File, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.filter { it.isFile && now - it.lastModified() > KEPT_MS }?.forEach { it.delete() }
    }
}
