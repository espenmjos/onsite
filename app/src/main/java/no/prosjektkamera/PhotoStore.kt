package no.prosjektkamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All data lives as plain folders and media files under Pictures/Onsite
 * on shared storage, so it survives uninstall and is reachable from file
 * managers and USB. The folder tree on disk *is* the data model, so a future
 * cloud sync can mirror it 1:1.
 *
 * A .nomedia file in the root keeps job photos out of the system gallery
 * unless the user opts in.
 */
class PhotoStore(private val context: Context) {
    private val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
    val root: File = File(pictures, "Onsite")
    private val noMedia get() = File(root, ".nomedia")

    /** Creates the root on first run and moves over files from earlier storage locations. */
    fun init() {
        // The app used to be called Prosjektkamera.
        val renamed = File(pictures, "Prosjektkamera")
        if (renamed.isDirectory) {
            if (!root.exists()) {
                renamed.renameTo(root)
            } else {
                renamed.listFiles().orEmpty().forEach { src ->
                    val dest = File(root, src.name)
                    if (!dest.exists() && !src.renameTo(dest)) {
                        src.copyRecursively(dest)
                        src.deleteRecursively()
                    }
                }
                renamed.delete()
            }
            if (visibleInGallery) scan(mediaUnder(root), force = true)
        }
        if (!root.exists()) {
            root.mkdirs()
            noMedia.createNewFile()
        }
        val legacy = File(context.getExternalFilesDir(null), "Prosjekter")
        if (legacy.isDirectory) {
            legacy.listFiles().orEmpty().forEach { src ->
                val dest = File(root, src.name)
                if (!dest.exists()) {
                    src.copyRecursively(dest)
                    src.deleteRecursively()
                }
            }
            legacy.delete()
        }
        changed()
    }

    /** Bumped after every mutation; composables read it to re-list. */
    val version = mutableIntStateOf(0)

    private fun changed() { version.intValue++ }

    var visibleInGallery: Boolean
        get() = !noMedia.exists()
        set(visible) {
            if (visible) noMedia.delete() else noMedia.createNewFile()
            // Ask the media scanner to (de)index everything under root.
            scan(mediaUnder(root), force = true)
            changed()
        }

    /** Keeps the gallery index in sync; scanning a deleted path removes its entry. */
    private fun scan(files: List<File>, force: Boolean = false) {
        if ((force || visibleInGallery) && files.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, files.map { it.path }.toTypedArray(), null, null)
        }
    }

    private fun mediaUnder(dir: File) = dir.walkTopDown().filter { it.isMedia }.toList()

    fun relativePath(dir: File): String = dir.relativeTo(root).path

    fun subfolders(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().sortedBy { it.name.lowercase() }

    /** Summary shown on folder rows and in the folder header. */
    data class FolderInfo(
        val created: Long,
        val lastAdded: Long?,
        val photos: Int,
        val videos: Int,
        val audio: Int,
        /** Newest photo or video, for the folder thumbnail. */
        val cover: File?,
    ) {
        val total get() = photos + videos + audio
        val lastActivity get() = lastAdded ?: created
    }

    fun folderInfo(dir: File): FolderInfo {
        val files = mediaUnder(dir)
        val newest = files.maxByOrNull { it.lastModified() }
        return FolderInfo(
            created = createdAt(dir, files),
            lastAdded = newest?.lastModified(),
            photos = files.count { it.isPhoto },
            videos = files.count { it.isVideo },
            audio = files.count { it.isAudio },
            cover = files.filter { !it.isAudio }.maxByOrNull { it.lastModified() },
        )
    }

    /**
     * Creation time is stored in a small hidden file, since the filesystem doesn't
     * keep one reliably. Folders made before this existed get a best guess, which
     * is then written down so it stays stable.
     */
    private fun createdAt(dir: File, files: List<File>): Long {
        val marker = File(dir, CREATED_FILE)
        marker.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()?.let { return it }
        val guess = (files.map { it.lastModified() } + dir.lastModified()).min()
        runCatching { marker.writeText(guess.toString()) }
        return guess
    }

    fun storageSummary(): Pair<Int, Long> {
        val files = mediaUnder(root)
        return files.size to files.sumOf { it.length() }
    }

    /** Photos, videos and audio recordings in [dir], newest first. */
    fun media(dir: File): List<File> =
        dir.listFiles { f -> f.isMedia }.orEmpty()
            .sortedByDescending { it.name.substringAfter('_') }

    fun countMediaRecursive(dir: File): Int = dir.walkTopDown().count { it.isMedia }

    /** Every folder below root, depth-first, for "move to" pickers. */
    fun allFolders(): List<File> =
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isDirectory && it != root }
            .sortedBy { relativePath(it).lowercase() }.toList()

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val clean = sanitize(name)
        val dir = File(parent, clean)
        require(!dir.exists()) { "\"$clean\" finnes allerede" }
        check(dir.mkdirs()) { "Kunne ikke opprette mappe" }
        File(dir, CREATED_FILE).writeText(System.currentTimeMillis().toString())
        changed()
        dir
    }

    fun renameFolder(dir: File, newName: String): Result<File> = runCatching {
        val target = File(dir.parentFile, sanitize(newName))
        require(!target.exists()) { "\"${target.name}\" finnes allerede" }
        val before = mediaUnder(dir)
        check(dir.renameTo(target)) { "Kunne ikke gi nytt navn" }
        scan(before + mediaUnder(target))
        changed()
        target
    }

    fun deleteFolder(dir: File) {
        val before = mediaUnder(dir)
        dir.deleteRecursively()
        scan(before)
        changed()
    }

    fun deletePhotos(files: Collection<File>) {
        files.forEach { it.delete() }
        scan(files.toList())
        changed()
    }

    fun movePhotos(files: Collection<File>, dest: File) {
        val targets = files.map { f ->
            val target = uniqueFile(dest, f.nameWithoutExtension, f.extension)
            if (!f.renameTo(target)) {
                f.copyTo(target)
                f.delete()
            }
            target
        }
        scan(files.toList() + targets)
        changed()
    }

    /** A new, not-yet-existing file to capture into. */
    fun newPhotoFile(dir: File): File = uniqueFile(dir, "IMG_${stamp()}", "jpg")

    fun newVideoFile(dir: File): File = uniqueFile(dir, "VID_${stamp()}", "mp4")

    fun newAudioFile(dir: File): File = uniqueFile(dir, "LYD_${stamp()}", "m4a")

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    fun mediaSaved(file: File) {
        scan(listOf(file))
        changed()
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 2
        while (f.exists()) f = File(dir, "${base}_${i++}.$ext")
        return f
    }

    companion object {
        private const val CREATED_FILE = ".opprettet"
    }

    private fun sanitize(name: String): String {
        val s = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").trim('.', ' ')
        require(s.isNotEmpty()) { "Ugyldig navn" }
        return s
    }
}

fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

val File.isPhoto: Boolean get() = extension.equals("jpg", true)
val File.isVideo: Boolean get() = extension.equals("mp4", true)
val File.isAudio: Boolean get() = extension.equals("m4a", true)
val File.isMedia: Boolean get() = isFile && (isPhoto || isVideo || isAudio)

val File.mimeType: String
    get() = when {
        isVideo -> "video/mp4"
        isAudio -> "audio/mp4"
        else -> "image/jpeg"
    }
