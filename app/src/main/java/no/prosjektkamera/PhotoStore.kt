package no.prosjektkamera

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A file or folder inside the storage root, identified by its path relative to the
 * root ("" is the root itself, "Job 1/Exterior/IMG_x.jpg" a photo). Two Docs are
 * equal when they point at the same path, whatever metadata they were read with.
 */
class Doc(val path: String, val isDirectory: Boolean, val length: Long = 0, val lastModified: Long = 0) {
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "")
    val nameWithoutExtension: String get() = name.substringBeforeLast('.', name)
    val isRoot: Boolean get() = path.isEmpty()
    val parent: Doc get() = Doc(path.substringBeforeLast('/', ""), true)
    fun child(name: String, isDirectory: Boolean = false) =
        Doc(if (path.isEmpty()) name else "$path/$name", isDirectory)
    fun isInside(other: Doc) = other.isRoot || path == other.path || path.startsWith(other.path + "/")

    override fun equals(other: Any?) = other is Doc && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path
}

/**
 * All data lives as plain folders and media files in a folder the user picks once
 * (normally Pictures/Onsite) through the Storage Access Framework. That needs no
 * special permission, survives uninstalling the app, and the folder tree on disk
 * *is* the data model, so a future cloud sync can mirror it 1:1.
 *
 * Only folders on the device's own storage are accepted: their document IDs are
 * plain paths ("primary:Pictures/Onsite/Job 1"), which lets us address any item
 * directly instead of searching for it.
 *
 * A .nomedia file in the root keeps job photos out of the system gallery unless
 * the user opts in. Calls do disk/IPC work: run them off the main thread.
 */
class PhotoStore(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** The granted folder, or null until the user has picked one (or access was lost). */
    var treeUri by mutableStateOf<Uri?>(validTree(prefs.getString("treeUri", null)?.let(Uri::parse)))
        private set

    val root = Doc("", true)

    /** Bumped after every mutation; composables read it to re-load. */
    val version = mutableIntStateOf(0)

    private fun changed() { version.intValue++ }

    // ---------------------------------------------------------------- setup

    private fun validTree(uri: Uri?): Uri? = uri?.takeIf { u ->
        resolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission && it.isWritePermission }
    }

    /** Takes over a folder picked with ACTION_OPEN_DOCUMENT_TREE. Returns false if it can't be used. */
    fun adoptTree(uri: Uri): Boolean {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return false
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }.getOrElse { return false }
        // Let go of any previously used folder.
        resolver.persistedUriPermissions.filter { it.uri != uri }.forEach {
            runCatching { resolver.releasePersistableUriPermission(it.uri, flags) }
        }
        prefs.edit().putString("treeUri", uri.toString()).apply()
        treeUri = uri
        init()
        return true
    }

    /** Human-readable location of the storage folder, e.g. "Pictures › Onsite". */
    val locationLabel: String?
        get() = treeUri?.let { DocumentsContract.getTreeDocumentId(it).substringAfter(':').replace("/", " › ") }

    private fun init() {
        // A new (empty) folder starts hidden from the gallery; an existing one keeps its choice.
        if (!exists(noMedia) && children(root).none { !it.name.startsWith(".") }) createFile(root, ".nomedia")
        purgeExpiredTrash()
        changed()
    }

    fun onStart() {
        if (treeUri != null) runCatching { purgeExpiredTrash() }
    }

    // ---------------------------------------------------------------- document plumbing

    private val tree get() = treeUri ?: error("No storage folder chosen")
    private val rootDocId get() = DocumentsContract.getTreeDocumentId(tree)
    private fun docId(path: String) = if (path.isEmpty()) rootDocId else "$rootDocId/$path"

    fun uri(doc: Doc): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId(doc.path))

    /** All children of [dir], including hidden sidecar files. */
    fun children(dir: Doc): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId(dir.path))
        val cols = arrayOf(
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Doc>()
        runCatching {
            resolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    out += Doc(
                        dir.child(name).path,
                        c.getString(1) == Document.MIME_TYPE_DIR,
                        if (c.isNull(2)) 0 else c.getLong(2),
                        if (c.isNull(3)) 0 else c.getLong(3),
                    )
                }
            }
        }
        return out
    }

    fun exists(doc: Doc): Boolean = runCatching {
        resolver.query(uri(doc), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** Size on disk as reported by the provider, or -1 if unknown. */
    fun sizeOf(doc: Doc): Long = runCatching {
        resolver.query(uri(doc), arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    private fun docPath(uri: Uri): String =
        DocumentsContract.getDocumentId(uri).removePrefix(rootDocId).removePrefix("/")

    /** Creates an empty file named exactly [name] (octet-stream keeps the provider from adding an extension). */
    private fun createFile(dir: Doc, name: String): Doc {
        val created = DocumentsContract.createDocument(resolver, uri(dir), "application/octet-stream", name)
            ?: error(context.getString(R.string.err_create))
        return Doc(docPath(created), false)
    }

    private fun createDir(dir: Doc, name: String): Doc {
        val created = DocumentsContract.createDocument(resolver, uri(dir), Document.MIME_TYPE_DIR, name)
            ?: error(context.getString(R.string.err_create))
        return Doc(docPath(created), true)
    }

    private fun delete(doc: Doc) = runCatching { DocumentsContract.deleteDocument(resolver, uri(doc)) }

    fun openInput(doc: Doc): InputStream = resolver.openInputStream(uri(doc)) ?: error("Can't read $doc")
    fun openOutput(doc: Doc): OutputStream = resolver.openOutputStream(uri(doc), "wt") ?: error("Can't write $doc")
    fun openFd(doc: Doc, mode: String): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri(doc), mode) ?: error("Can't open $doc")

    private fun readText(doc: Doc): String? = runCatching { openInput(doc).bufferedReader().use { it.readText() } }.getOrNull()

    private fun writeText(dir: Doc, name: String, text: String) {
        val doc = dir.child(name).takeIf { exists(it) } ?: createFile(dir, name)
        openOutput(doc).bufferedWriter().use { it.write(text) }
    }

    /** Renames [doc] in place; returns the renamed Doc. */
    private fun rename(doc: Doc, newName: String): Doc {
        if (doc.name == newName) return doc
        val renamed = DocumentsContract.renameDocument(resolver, uri(doc), newName)
            ?: error(context.getString(R.string.err_rename))
        return Doc(docPath(renamed), doc.isDirectory, doc.length, doc.lastModified)
    }

    /** Moves [doc] into [dest] under [targetName] (renaming first if needed); carries a file's sidecars along. */
    private fun moveDoc(doc: Doc, dest: Doc, targetName: String): Doc {
        // Sidecars are looked up by name rather than listed, in case the provider hides dot-files.
        val sidecars = if (doc.isDirectory) emptyList()
        else SIDECAR_SUFFIXES.map { doc.parent.child(".${doc.name}$it") }.filter { exists(it) }
        var src = doc
        if (targetName != doc.name) src = rename(doc, uniqueName(doc.parent, targetName))
        val moved = if (src.parent == dest) src else {
            val newUri = DocumentsContract.moveDocument(resolver, uri(src), uri(src.parent), uri(dest))
                ?: error("Move failed")
            Doc(docPath(newUri), src.isDirectory, src.length, src.lastModified)
        }
        if (moved.name != targetName) rename(moved, targetName)
        val target = dest.child(targetName, doc.isDirectory)
        sidecars.forEach { sc ->
            val newName = ".$targetName." + sc.name.removePrefix(".${doc.name}.")
            runCatching { moveDoc(sc, dest, uniqueName(dest, newName)) }
        }
        return Doc(target.path, doc.isDirectory, doc.length, doc.lastModified)
    }

    /** [name], or "name (2)"/"base_2.ext" style variants, whichever is free in [dir]. */
    private fun uniqueName(dir: Doc, name: String, isDirectory: Boolean = false): String {
        val taken = children(dir).map { it.name }.toSet()
        if (name !in taken) return name
        var i = 2
        while (true) {
            val candidate = if (isDirectory || !name.contains('.') || name.startsWith(".")) "$name ($i)"
            else "${name.substringBeforeLast('.')}_$i.${name.substringAfterLast('.')}"
            if (candidate !in taken) return candidate
            i++
        }
    }

    // ---------------------------------------------------------------- gallery visibility

    private val noMedia get() = root.child(".nomedia")

    fun isVisibleInGallery(): Boolean = !exists(noMedia)

    fun setVisibleInGallery(visible: Boolean) {
        if (visible) delete(noMedia) else if (!exists(noMedia)) createFile(root, ".nomedia")
        scan(mediaUnder(root), force = true)
        changed()
    }

    /** The real file path behind a document, for the media scanner (device storage only). */
    private fun realPath(doc: Doc): String {
        val id = docId(doc.path)
        val volume = id.substringBefore(':')
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$base/${id.substringAfter(':')}"
    }

    /** Keeps the gallery index in sync; scanning a deleted path removes its entry. */
    private fun scan(docs: List<Doc>, force: Boolean = false) {
        if (docs.isEmpty()) return
        if (force || isVisibleInGallery()) {
            MediaScannerConnection.scanFile(context, docs.map { realPath(it) }.toTypedArray(), null, null)
        }
    }

    // ---------------------------------------------------------------- listing

    fun subfolders(dir: Doc): List<Doc> =
        children(dir).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }

    /** Photos, videos and audio recordings in [dir], newest first. */
    fun media(dir: Doc): List<Doc> = children(dir).filter { it.isMedia }.sortedByDescending { it.lastModified }

    /** Media files below [dir], skipping hidden folders such as the trash. */
    private fun mediaUnder(dir: Doc): List<Doc> {
        val out = ArrayList<Doc>()
        fun walk(d: Doc) {
            children(d).forEach { c ->
                when {
                    c.isDirectory -> if (!c.name.startsWith(".")) walk(c)
                    c.isMedia -> out += c
                }
            }
        }
        if (dir.isDirectory) walk(dir) else out += dir
        return out
    }

    /** All media files in [items], expanding folders recursively. */
    fun mediaIn(items: Collection<Doc>): List<Doc> = items.flatMap { mediaUnder(it) }

    /** Summary shown on folder rows and in the folder header. */
    data class FolderInfo(
        val created: Long,
        val lastAdded: Long?,
        val photos: Int,
        val videos: Int,
        val audio: Int,
        /** Newest photo or video, for the folder thumbnail. */
        val cover: Doc?,
    ) {
        val total get() = photos + videos + audio
        val lastActivity get() = lastAdded ?: created
    }

    fun folderInfo(dir: Doc): FolderInfo {
        val files = mediaUnder(dir)
        return FolderInfo(
            created = createdAt(dir, files),
            lastAdded = files.maxOfOrNull { it.lastModified },
            photos = files.count { it.isPhoto },
            videos = files.count { it.isVideo },
            audio = files.count { it.isAudio },
            cover = files.filter { !it.isAudio }.maxByOrNull { it.lastModified },
        )
    }

    /** Everything a folder screen shows, loaded in one go off the main thread. */
    class FolderContent(
        val subfolders: List<Doc>,
        val infos: Map<Doc, FolderInfo>,
        val media: List<Doc>,
        val info: FolderInfo?,
        val noted: Set<Doc>,
        val note: String?,
    )

    fun loadFolder(dir: Doc, sortByRecent: Boolean): FolderContent {
        val kids = children(dir)
        val subs = kids.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
        val infos = subs.associateWith { folderInfo(it) }
        return FolderContent(
            subfolders = if (sortByRecent) subs.sortedByDescending { infos.getValue(it).lastActivity } else subs,
            infos = infos,
            media = kids.filter { it.isMedia }.sortedByDescending { it.lastModified },
            info = if (dir.isRoot) null else folderInfo(dir),
            noted = notedIn(dir, kids),
            note = if (dir.isRoot) null else note(dir),
        )
    }

    /** Files in [dir] that have a note. Uses the listing when it includes dot-files, else checks each file. */
    private fun notedIn(dir: Doc, kids: List<Doc>): Set<Doc> {
        if (kids.any { it.name.startsWith(".") }) {
            return kids.filter { it.name.startsWith(".") && it.name.endsWith(NOTE_SUFFIX) && it.name != FOLDER_NOTE }
                .map { dir.child(it.name.removePrefix(".").removeSuffix(NOTE_SUFFIX)) }.toSet()
        }
        return kids.filter { it.isMedia && exists(noteDoc(it)) }.toSet()
    }

    /**
     * Creation time is stored in a small hidden file, since the filesystem doesn't
     * keep one. Folders made before this existed get a best guess, which is then
     * written down so it stays stable.
     */
    private fun createdAt(dir: Doc, files: List<Doc>): Long {
        readText(dir.child(CREATED_FILE))?.trim()?.toLongOrNull()?.let { return it }
        val guess = files.minOfOrNull { it.lastModified } ?: System.currentTimeMillis()
        runCatching { writeText(dir, CREATED_FILE, guess.toString()) }
        return guess
    }

    fun storageSummary(): Pair<Int, Long> {
        val files = mediaUnder(root)
        return files.size to files.sumOf { it.length }
    }

    // ---------------------------------------------------------------- notes & addresses

    /**
     * Notes are plain-text sidecar files next to what they describe, hidden with a
     * leading dot: ".IMG_x.jpg.notat.txt" for a file, ".notat.txt" inside a folder.
     * Being ordinary files, they move with folders and will sync like everything else.
     */
    private fun noteDoc(item: Doc) =
        if (item.isDirectory) item.child(FOLDER_NOTE) else item.parent.child(".${item.name}$NOTE_SUFFIX")

    fun note(item: Doc): String? = readText(noteDoc(item))?.trim()?.takeIf { it.isNotEmpty() }

    fun setNote(item: Doc, text: String) {
        val doc = noteDoc(item)
        if (text.isBlank()) delete(doc) else writeText(doc.parent, doc.name, text.trim())
        changed()
    }

    /** A manually corrected address for a photo/video, overriding the GPS lookup. */
    private fun addressDoc(item: Doc) = item.parent.child(".${item.name}.adresse.txt")

    fun customAddress(item: Doc): String? = readText(addressDoc(item))?.trim()?.takeIf { it.isNotEmpty() }

    fun setCustomAddress(item: Doc, text: String?) {
        val doc = addressDoc(item)
        if (text.isNullOrBlank()) delete(doc) else writeText(doc.parent, doc.name, text.trim())
        changed()
    }

    // ---------------------------------------------------------------- folders

    fun createFolder(parent: Doc, name: String): Result<Doc> = runCatching {
        val clean = sanitize(name)
        require(children(parent).none { it.name.equals(clean, ignoreCase = true) }) {
            context.getString(R.string.err_exists, clean)
        }
        val dir = createDir(parent, clean)
        writeText(dir, CREATED_FILE, System.currentTimeMillis().toString())
        changed()
        dir
    }

    fun renameFolder(dir: Doc, newName: String): Result<Doc> = runCatching {
        val clean = sanitize(newName)
        require(children(dir.parent).none { it.name.equals(clean, ignoreCase = true) && it != dir }) {
            context.getString(R.string.err_exists, clean)
        }
        val before = mediaUnder(dir)
        val renamed = rename(dir, clean)
        scan(before + mediaUnder(renamed))
        changed()
        renamed
    }

    // ---------------------------------------------------------------- move & trash

    fun deleteFolder(dir: Doc) = deleteItems(listOf(dir))

    /** Moves files and/or whole folders into [dest], renaming on name clashes. */
    fun moveItems(items: Collection<Doc>, dest: Doc) {
        val before = mediaIn(items)
        val targets = items.filter { !dest.isInside(it) || !it.isDirectory }.map { doc ->
            moveDoc(doc, dest, uniqueName(dest, doc.name, doc.isDirectory))
        }
        scan(before + mediaIn(targets))
        changed()
    }

    /** Deleted items wait here for [TRASH_DAYS] days. Hidden, so it's skipped everywhere else. */
    private val trashDir get() = root.child(TRASH, isDirectory = true)

    data class TrashEntry(val dir: Doc, val item: Doc, val originalPath: String, val deletedAt: Long) {
        val expiresAt get() = deletedAt + TRASH_DAYS * 86_400_000L
    }

    private fun ensureTrash(): Doc {
        if (!exists(trashDir)) {
            createDir(root, TRASH)
            createFile(trashDir, ".nomedia") // never show trash in the gallery
        }
        return trashDir
    }

    /** Moves files and/or whole folders to the trash. */
    fun deleteItems(items: Collection<Doc>) {
        val gone = mediaIn(items)
        val trash = ensureTrash()
        val now = System.currentTimeMillis()
        items.forEachIndexed { i, item ->
            val entry = createDir(trash, "${now}_$i")
            writeText(entry, ".info", "${item.path}\n$now\n")
            moveDoc(item, entry, item.name)
        }
        scan(gone)
        changed()
    }

    fun trashEntries(): List<TrashEntry> {
        if (!exists(trashDir)) return emptyList()
        return children(trashDir).filter { it.isDirectory }.mapNotNull { dir ->
            val info = readText(dir.child(".info"))?.lines() ?: return@mapNotNull null
            val item = children(dir).firstOrNull { !it.name.startsWith(".") } ?: return@mapNotNull null
            TrashEntry(dir, item, info.getOrElse(0) { "" }, info.getOrNull(1)?.toLongOrNull() ?: dir.lastModified)
        }.sortedByDescending { it.deletedAt }
    }

    fun restore(entries: Collection<TrashEntry>) {
        val restored = entries.map { e ->
            val parent = ensureDirs(Doc(e.originalPath, false).parent)
            val target = moveDoc(e.item, parent, uniqueName(parent, e.item.name, e.item.isDirectory))
            delete(e.dir)
            target
        }
        scan(mediaIn(restored))
        changed()
    }

    fun deleteForever(entries: Collection<TrashEntry>) {
        entries.forEach { delete(it.dir) }
        changed()
    }

    private fun purgeExpiredTrash() {
        val now = System.currentTimeMillis()
        trashEntries().filter { it.expiresAt < now }.forEach { delete(it.dir) }
    }

    /** Creates any missing folders along [dir]'s path. */
    private fun ensureDirs(dir: Doc): Doc {
        if (dir.isRoot || exists(dir)) return dir
        val parent = ensureDirs(dir.parent)
        return createDir(parent, dir.name)
    }

    // ---------------------------------------------------------------- new files

    /** Creates a new, empty file to capture or import into. */
    fun newPhoto(dir: Doc): Doc = createFile(dir, uniqueName(dir, "IMG_${stamp()}.jpg"))
    fun newVideo(dir: Doc): Doc = createFile(dir, uniqueName(dir, "VID_${stamp()}.mp4"))
    fun newAudio(dir: Doc): Doc = createFile(dir, uniqueName(dir, "LYD_${stamp()}.m4a"))

    /** A new file in [dir] named after [name] (as given by the sharing app). */
    fun newImport(dir: Doc, name: String, fallbackExt: String): Doc {
        val clean = name.replace(Regex("""[\\/:*?"<>|]"""), "-").trim().trimStart('.')
        val base = clean.substringBeforeLast('.', clean).ifBlank { "IMPORT_${stamp()}" }
        val ext = clean.substringAfterLast('.', "").ifBlank { fallbackExt }
        return createFile(dir, uniqueName(dir, "$base.$ext"))
    }

    /** Copies a finished local file (e.g. a camera capture in the cache) into [doc]. */
    fun writeFrom(doc: Doc, file: File) {
        file.inputStream().use { input -> openOutput(doc).use { input.copyTo(it) } }
    }

    /** Removes a file that was created but never finished (failed capture, discarded recording). */
    fun discard(doc: Doc) {
        delete(doc)
        changed()
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())

    fun mediaSaved(docs: List<Doc>) {
        scan(docs)
        changed()
    }

    fun mediaSaved(doc: Doc) = mediaSaved(listOf(doc))

    private fun sanitize(name: String): String {
        val s = name.trim().replace(Regex("""[\\/:*?"<>|]"""), "-").trim('.', ' ')
        require(s.isNotEmpty()) { context.getString(R.string.err_invalid_name) }
        return s
    }

    companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val CREATED_FILE = ".opprettet"
        private const val FOLDER_NOTE = ".notat.txt"
        private const val NOTE_SUFFIX = ".notat.txt"
        private const val TRASH = ".papirkurv"
        private val SIDECAR_SUFFIXES = listOf(".notat.txt", ".adresse.txt")
        const val TRASH_DAYS = 30

        /** Where the folder picker should open: Pictures/Onsite (created if possible), else Pictures. */
        fun suggestedStartUri(): Uri {
            val pictures = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
            val onsite = File(pictures, "Onsite")
            runCatching { onsite.mkdirs() } // apps may create folders in Pictures without any permission
            val id = if (onsite.isDirectory) "primary:Pictures/Onsite" else "primary:Pictures"
            return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, id)
        }
    }
}

private val photoExt = setOf("jpg", "jpeg", "png", "heic", "heif", "webp")
private val videoExt = setOf("mp4", "mov", "3gp", "mkv", "webm")
private val audioExt = setOf("m4a", "mp3", "aac", "wav", "ogg", "opus", "amr", "3ga")

val Doc.isPhoto: Boolean get() = extension.lowercase() in photoExt
val Doc.isVideo: Boolean get() = extension.lowercase() in videoExt
val Doc.isAudio: Boolean get() = extension.lowercase() in audioExt
val Doc.isMedia: Boolean get() = !isDirectory && !name.startsWith(".") && (isPhoto || isVideo || isAudio)

val Doc.mimeType: String
    get() = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        ?: when {
            isVideo -> "video/*"
            isAudio -> "audio/*"
            else -> "image/*"
        }
