package no.prosjektkamera

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports projects/folders with their structure intact, either into a folder the
 * user picks with the system picker (Google Drive, SD card, USB …) or as a ZIP.
 * Notes and corrected addresses become a readable Notes.txt per folder; Onsite's
 * hidden helper files are left out.
 */
class Exporter(private val context: Context, private val store: PhotoStore) {
    private val resolver = context.contentResolver

    /** Number of media files [items] will export, for the progress display. */
    fun count(items: List<Doc>): Int = store.mediaIn(items).size

    /**
     * Copies [items] into the folder [treeUri] points at. Folders are recreated with
     * their subfolders. Calls [progress] after each file. Returns the number of files copied.
     */
    fun exportToTree(treeUri: Uri, items: List<Doc>, progress: (Int) -> Unit): Int {
        val target = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        var done = 0
        fun copyFile(doc: Doc, parent: Uri) {
            val created = DocumentsContract.createDocument(resolver, parent, doc.mimeType, doc.name)
                ?: error("Could not create ${doc.name}")
            store.openInput(doc).use { input -> resolver.openOutputStream(created, "w")!!.use { input.copyTo(it) } }
            progress(++done)
        }
        fun copyDir(dir: Doc, parent: Uri) {
            val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, dir.name)
                ?: error("Could not create folder ${dir.name}")
            val kids = store.children(dir).filter { !it.name.startsWith(".") }
            kids.filter { it.isMedia }.sortedBy { it.name }.forEach { copyFile(it, created) }
            notesText(dir, kids)?.let { text ->
                val notes = DocumentsContract.createDocument(resolver, created, "text/plain", notesFileName)
                notes?.let { resolver.openOutputStream(it, "w")!!.use { o -> o.write(text.toByteArray()) } }
            }
            kids.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { copyDir(it, created) }
        }
        items.forEach { if (it.isDirectory) copyDir(it, target) else copyFile(it, target) }
        return done
    }

    /**
     * Packs [items] into a ZIP named after [name] in the app cache (ready to share).
     * Photos and video are already compressed, so a fast compression level is used.
     */
    fun zip(items: List<Doc>, name: String, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "${name.replace(Regex("""[\\/:*?"<>|]"""), "-")}.zip")
        var done = 0
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            fun addFile(doc: Doc, prefix: String) {
                zip.putNextEntry(ZipEntry(prefix + doc.name).apply { time = doc.lastModified })
                store.openInput(doc).use { it.copyTo(zip) }
                zip.closeEntry()
                progress(++done)
            }
            fun addDir(d: Doc, prefix: String) {
                val path = "$prefix${d.name}/"
                zip.putNextEntry(ZipEntry(path)); zip.closeEntry()
                val kids = store.children(d).filter { !it.name.startsWith(".") }
                kids.filter { it.isMedia }.sortedBy { it.name }.forEach { addFile(it, path) }
                notesText(d, kids)?.let { text ->
                    zip.putNextEntry(ZipEntry(path + notesFileName))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
                kids.filter { it.isDirectory }.sortedBy { it.name.lowercase() }.forEach { addDir(it, path) }
            }
            items.forEach { if (it.isDirectory) addDir(it, "") else addFile(it, "") }
        }
        return file
    }

    private val notesFileName get() = context.getString(R.string.notes_file_name)

    /** The folder note plus each file's note and corrected address, or null if there are none. */
    private fun notesText(dir: Doc, kids: List<Doc>): String? {
        val sb = StringBuilder()
        store.note(dir)?.let { sb.append(context.getString(R.string.folder_note_heading)).append("\n").append(it).append("\n\n") }
        kids.filter { it.isMedia }.sortedBy { it.name }.forEach { f ->
            val note = store.note(f)
            val address = store.customAddress(f)
            if (note != null || address != null) {
                sb.append(f.name).append("\n")
                address?.let { sb.append(context.getString(R.string.address_hint)).append(": ").append(it).append("\n") }
                note?.let { sb.append(it).append("\n") }
                sb.append("\n")
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }?.plus("\n")
    }

    companion object {
        const val EXPORT_DIR = "exports"
    }
}
