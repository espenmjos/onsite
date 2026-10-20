package no.prosjektkamera

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All data lives as plain folders and JPEG files under the app's own external
 * files dir (Android/data/no.prosjektkamera/files/Prosjekter). The folder tree
 * on disk *is* the data model, so a future cloud sync can mirror it 1:1.
 *
 * Files here are not indexed by the system gallery, so job photos stay out of
 * the normal camera roll.
 */
class PhotoStore(context: Context) {
    val root: File = File(context.getExternalFilesDir(null), "Prosjekter").apply { mkdirs() }

    /** Bumped after every mutation; composables read it to re-list. */
    val version = mutableIntStateOf(0)

    private fun changed() { version.intValue++ }

    fun relativePath(dir: File): String = dir.relativeTo(root).path

    fun subfolders(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name.lowercase() }

    fun photos(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.extension.equals("jpg", true) }.orEmpty()
            .sortedByDescending { it.name }

    fun countPhotosRecursive(dir: File): Int =
        dir.walkTopDown().count { it.isFile && it.extension.equals("jpg", true) }

    /** Every folder below root, depth-first, for "move to" pickers. */
    fun allFolders(): List<File> =
        root.walkTopDown().filter { it.isDirectory && it != root }
            .sortedBy { relativePath(it).lowercase() }.toList()

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val clean = sanitize(name)
        val dir = File(parent, clean)
        require(!dir.exists()) { "\"$clean\" finnes allerede" }
        check(dir.mkdirs()) { "Kunne ikke opprette mappe" }
        changed()
        dir
    }

    fun renameFolder(dir: File, newName: String): Result<File> = runCatching {
        val target = File(dir.parentFile, sanitize(newName))
        require(!target.exists()) { "\"${target.name}\" finnes allerede" }
        check(dir.renameTo(target)) { "Kunne ikke gi nytt navn" }
        changed()
        target
    }

    fun deleteFolder(dir: File) {
        dir.deleteRecursively()
        changed()
    }

    fun deletePhotos(files: Collection<File>) {
        files.forEach { it.delete() }
        changed()
    }

    fun movePhotos(files: Collection<File>, dest: File) {
        files.forEach { f ->
            val target = uniqueFile(dest, f.nameWithoutExtension, f.extension)
            if (!f.renameTo(target)) {
                f.copyTo(target)
                f.delete()
            }
        }
        changed()
    }

    /** A new, not-yet-existing file to capture into. */
    fun newPhotoFile(dir: File): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        return uniqueFile(dir, "IMG_$stamp", "jpg")
    }

    fun photoSaved() = changed()

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 2
        while (f.exists()) f = File(dir, "${base}_${i++}.$ext")
        return f
    }

    private fun sanitize(name: String): String {
        val s = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").trim('.', ' ')
        require(s.isNotEmpty()) { "Ugyldig navn" }
        return s
    }
}
