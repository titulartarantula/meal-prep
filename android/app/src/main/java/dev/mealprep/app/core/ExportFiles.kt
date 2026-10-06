package dev.mealprep.app.core

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.net.URLDecoder

/** An export the server made, saved in the app's cache under its file name ([name]). */
data class ExportFile(val name: String, val file: File)

/** Recipe exports: kept in cacheDir/exports (shared through the FileProvider for Send…), copied out for Save. */
object ExportFiles {
    const val DIR = "exports"
    /** The FileProvider authority suffix (AndroidManifest: ${applicationId}.pages, paths in res/xml/page_paths.xml). */
    const val AUTHORITY_SUFFIX = ".pages"
    const val MIME = "application/json"
    private const val KEPT_MS = 24L * 3600 * 1000

    private val STAR = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""")
    private val PLAIN = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]+))""")
    private val UNSAFE = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")

    /** The file name in a Content-Disposition header (filename*= first, then filename=), made safe for a file
     *  system; null when there is none. */
    fun fileName(contentDisposition: String?): String? {
        val h = contentDisposition ?: return null
        val raw = STAR.find(h)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it.trim(), "UTF-8") }.getOrNull() }
            ?: PLAIN.find(h)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
        return raw?.replace(UNSAFE, "-")?.trim()?.trim('.', '-', ' ')?.take(120)?.ifBlank { null }
    }

    /** Writes [body] to [dir]/[name] (a whole file or nothing: written aside, then renamed); older exports pruned. */
    fun save(body: InputStream, dir: File, name: String, now: Long = System.currentTimeMillis()): File {
        dir.mkdirs()
        dir.listFiles()?.filter { it.isFile && now - it.lastModified() > KEPT_MS }?.forEach { it.delete() }
        val part = File(dir, ".$name.part")
        try {
            body.use { i -> part.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            val out = File(dir, name)
            out.delete()
            if (!part.renameTo(out)) throw java.io.IOException("rename failed")
            return out
        } finally {
            part.delete()
        }
    }

    /** Save to a file: the export's bytes into the document the user chose in the system picker. */
    fun copyTo(resolver: ContentResolver, file: File, uri: Uri) {
        val out = resolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no output stream")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Send…: the share sheet with the file (read access granted to whichever app is chosen). */
    fun sendIntent(uri: Uri, name: String, chooserTitle: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        return Intent.createChooser(send, chooserTitle)
    }
}
