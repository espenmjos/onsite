package no.prosjektkamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
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
        purgeExpiredTrash()
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

    /** Media files below [dir], skipping hidden folders such as the trash. */
    private fun mediaUnder(dir: File) =
        dir.walkTopDown().onEnter { it == dir || !it.name.startsWith(".") }.filter { it.isMedia }.toList()

    // ---------------------------------------------------------------- notes

    /**
     * Notes are plain-text sidecar files next to what they describe, hidden with a
     * leading dot: ".IMG_x.jpg.notat.txt" for a file, ".notat.txt" inside a folder.
     * Being ordinary files, they move with folders and will sync like everything else.
     */
    private fun noteFile(item: File) =
        if (item.isDirectory) File(item, ".notat.txt") else File(item.parentFile, ".${item.name}.notat.txt")

    fun note(item: File): String? =
        noteFile(item).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /** Files in [dir] that have a note, for badges in the grid. */
    fun notedFiles(dir: File): Set<File> =
        dir.list().orEmpty().filter { it.startsWith(".") && it.endsWith(".notat.txt") && it != ".notat.txt" }
            .map { File(dir, it.removePrefix(".").removeSuffix(".notat.txt")) }.toSet()

    fun setNote(item: File, text: String) {
        val f = noteFile(item)
        if (text.isBlank()) f.delete() else f.writeText(text.trim())
        changed()
    }

    /** A manually corrected address for a photo/video, overriding the GPS lookup. */
    private fun addressFile(item: File) = File(item.parentFile, ".${item.name}.adresse.txt")

    fun customAddress(item: File): String? =
        addressFile(item).takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun setCustomAddress(item: File, text: String?) {
        val f = addressFile(item)
        if (text.isNullOrBlank()) f.delete() else f.writeText(text.trim())
        changed()
    }

    /**
     * Moves a file's sidecars (".NAME.notat.txt", ".NAME.adresse.txt", …) along to [to].
     * Folders need nothing: their sidecars live inside them.
     */
    private fun moveSidecars(fromDir: File, fromName: String, to: File) {
        if (to.isDirectory) return
        val prefix = ".$fromName."
        fromDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }.orEmpty().forEach { src ->
            val dst = File(to.parentFile, ".${to.name}." + src.name.removePrefix(prefix))
            if (!src.renameTo(dst)) { src.copyTo(dst, overwrite = true); src.delete() }
        }
    }

    // ---------------------------------------------------------------- trash

    /** Deleted items wait here for [TRASH_DAYS] days. Hidden, so it's skipped everywhere else. */
    private val trashDir get() = File(root, ".papirkurv")

    data class TrashEntry(val dir: File, val item: File, val originalPath: String, val deletedAt: Long) {
        val expiresAt get() = deletedAt + TRASH_DAYS * 86_400_000L
    }

    fun trashEntries(): List<TrashEntry> =
        trashDir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val info = File(dir, ".info").takeIf { it.exists() }?.readLines() ?: return@mapNotNull null
            val item = dir.listFiles { f -> !f.name.startsWith(".") }?.firstOrNull() ?: return@mapNotNull null
            TrashEntry(dir, item, info.getOrElse(0) { "" }, info.getOrNull(1)?.toLongOrNull() ?: dir.lastModified())
        }.sortedByDescending { it.deletedAt }

    fun restore(entries: Collection<TrashEntry>) {
        val restored = entries.map { e ->
            val parent = File(root, e.originalPath).parentFile ?: root
            parent.mkdirs()
            val target = if (e.item.isDirectory) uniqueDir(parent, e.item.name)
            else uniqueFile(parent, e.item.nameWithoutExtension, e.item.extension)
            moveFile(e.item, target)
            e.dir.deleteRecursively()
            target
        }
        scan(mediaIn(restored))
        changed()
    }

    fun deleteForever(entries: Collection<TrashEntry>) {
        entries.forEach { it.dir.deleteRecursively() }
        changed()
    }

    private fun purgeExpiredTrash() {
        val now = System.currentTimeMillis()
        trashEntries().filter { it.expiresAt < now }.forEach { it.dir.deleteRecursively() }
    }

    /** Renames, falling back to copy+delete; carries a file's note along. */
    private fun moveFile(from: File, to: File) {
        if (!from.renameTo(to)) {
            from.copyRecursively(to)
            from.deleteRecursively()
        }
        moveSidecars(from.parentFile!!, from.name, to)
    }

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
            .sortedByDescending { it.lastModified() }

    fun countMediaRecursive(dir: File): Int = mediaUnder(dir).size

    /** Every folder below root, depth-first, for "move to" pickers. */
    fun allFolders(): List<File> =
        root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isDirectory && it != root }
            .sortedBy { relativePath(it).lowercase() }.toList()

    fun createFolder(parent: File, name: String): Result<File> = runCatching {
        val clean = sanitize(name)
        val dir = File(parent, clean)
        require(!dir.exists()) { context.getString(R.string.err_exists, clean) }
        check(dir.mkdirs()) { context.getString(R.string.err_create) }
        File(dir, CREATED_FILE).writeText(System.currentTimeMillis().toString())
        changed()
        dir
    }

    fun renameFolder(dir: File, newName: String): Result<File> = runCatching {
        val target = File(dir.parentFile, sanitize(newName))
        require(!target.exists()) { context.getString(R.string.err_exists, target.name) }
        val before = mediaUnder(dir)
        check(dir.renameTo(target)) { context.getString(R.string.err_rename) }
        scan(before + mediaUnder(target))
        changed()
        target
    }

    fun deleteFolder(dir: File) = deleteItems(listOf(dir))

    /** All media files in [items], expanding folders recursively. */
    fun mediaIn(items: Collection<File>): List<File> =
        items.flatMap { if (it.isDirectory) mediaUnder(it) else listOf(it) }

    /** Moves files and/or whole folders to the trash. */
    fun deleteItems(items: Collection<File>) {
        val gone = mediaIn(items)
        trashDir.mkdirs()
        File(trashDir, ".nomedia").createNewFile() // never show trash in the gallery
        val now = System.currentTimeMillis()
        items.forEachIndexed { i, item ->
            val entry = File(trashDir, "${now}_$i")
            entry.mkdirs()
            File(entry, ".info").writeText("${relativePath(item)}\n$now\n")
            moveFile(item, File(entry, item.name))
        }
        scan(gone)
        changed()
    }

    /** Moves files and/or whole folders into [dest], renaming on name clashes. */
    fun moveItems(items: Collection<File>, dest: File) {
        val before = mediaIn(items)
        val targets = items.map { f ->
            val target = if (f.isDirectory) uniqueDir(dest, f.name)
            else uniqueFile(dest, f.nameWithoutExtension, f.extension)
            moveFile(f, target)
            target
        }
        scan(before + mediaIn(targets))
        changed()
    }

    /** A new, not-yet-existing file to capture into. */
    fun newPhotoFile(dir: File): File = uniqueFile(dir, "IMG_${stamp()}", "jpg")

    fun newVideoFile(dir: File): File = uniqueFile(dir, "VID_${stamp()}", "mp4")

    fun newAudioFile(dir: File): File = uniqueFile(dir, "LYD_${stamp()}", "m4a")

    /** A free file name in [dir] based on [name] (as given by the sharing app). */
    fun newImportFile(dir: File, name: String, fallbackExt: String): File {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "-").trim().trimStart('.')
        val base = clean.substringBeforeLast('.', clean).ifBlank { "IMPORT_${stamp()}" }
        val ext = clean.substringAfterLast('.', "").ifBlank { fallbackExt }
        return uniqueFile(dir, base, ext)
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    fun imported(files: List<File>) {
        scan(files)
        changed()
    }

    fun mediaSaved(file: File) {
        scan(listOf(file))
        changed()
    }

    private fun uniqueDir(parent: File, name: String): File {
        var f = File(parent, name)
        var i = 2
        while (f.exists()) f = File(parent, "$name (${i++})")
        return f
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var f = File(dir, "$base.$ext")
        var i = 2
        while (f.exists()) f = File(dir, "${base}_${i++}.$ext")
        return f
    }

    companion object {
        private const val CREATED_FILE = ".opprettet"
        const val TRASH_DAYS = 30
    }

    private fun sanitize(name: String): String {
        val s = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").trim('.', ' ')
        require(s.isNotEmpty()) { context.getString(R.string.err_invalid_name) }
        return s
    }
}

fun hasStorageAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

private val photoExt = setOf("jpg", "jpeg", "png", "heic", "heif", "webp")
private val videoExt = setOf("mp4", "mov", "3gp", "mkv", "webm")
private val audioExt = setOf("m4a", "mp3", "aac", "wav", "ogg", "opus", "amr", "3ga")

val File.isPhoto: Boolean get() = extension.lowercase() in photoExt
val File.isVideo: Boolean get() = extension.lowercase() in videoExt
val File.isAudio: Boolean get() = extension.lowercase() in audioExt
val File.isMedia: Boolean get() = isFile && !name.startsWith(".") && (isPhoto || isVideo || isAudio)

val File.mimeType: String
    get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        ?: when {
            isVideo -> "video/*"
            isAudio -> "audio/*"
            else -> "image/*"
        }
