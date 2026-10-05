package dev.mealprep.app.ui.camera

import java.io.File
import java.util.UUID

/** Each recipe's pages live in their own folder under filesDir/pages until the import succeeds (or "Try again"
 *  needs them); abandoned folders are pruned after two weeks. */
class PageStore(private val root: File) {
    companion object {
        private val PAGE = Regex("""page\d{2}\.jpg""")
        fun pagesIn(dir: File): List<File> = dir.listFiles { f -> PAGE.matches(f.name) }.orEmpty().sortedBy { it.name }
    }

    fun newBatch(): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
    fun newFile(dir: File): File = File(dir, "p-${UUID.randomUUID()}.jpg")

    /** Rename to page01.jpg … in this order (through temp names, so swaps can't clobber); delete everything else. */
    fun commitOrder(dir: File, ordered: List<File>): List<File> {
        val tmp = ordered.mapIndexed { i, f -> File(dir, "tmp-$i.jpg").also { check(f.renameTo(it)) { "couldn't rename ${f.name}" } } }
        dir.listFiles().orEmpty().filter { it !in tmp }.forEach { it.deleteRecursively() }
        return tmp.mapIndexed { i, f -> File(dir, "page%02d.jpg".format(i + 1)).also { check(f.renameTo(it)) { "couldn't rename ${f.name}" } } }
    }

    fun discard(dir: File) { dir.deleteRecursively() }

    fun pruneOlderThan(cutoffMillis: Long) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.lastModified() < cutoffMillis }.forEach { it.deleteRecursively() }
    }
}
