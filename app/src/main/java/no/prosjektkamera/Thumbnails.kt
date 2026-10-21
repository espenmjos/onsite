package no.prosjektkamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/**
 * Small JPEG thumbnails (about 320 px) kept in the app cache, so grids and folder
 * covers show instantly instead of decoding full-size photos through the document
 * provider every time. Keyed by path, size and modification time, so edits refresh them.
 */
class Thumbnails(private val context: Context, private val store: PhotoStore) {
    private val dir get() = File(context.cacheDir, "thumbs").apply { mkdirs() }

    @OptIn(ExperimentalCoroutinesApi::class)
    val dispatcher = Dispatchers.IO.limitedParallelism(3)

    fun fileFor(doc: Doc): File {
        val key = MessageDigest.getInstance("SHA-1")
            .digest("${store.treeUri}|${doc.path}|${doc.length}|${doc.lastModified}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$key.jpg")
    }

    /** The cached thumbnail if it already exists (cheap enough for the main thread). */
    fun cached(doc: Doc): File? = fileFor(doc).takeIf { it.exists() }

    /** Returns the thumbnail, creating it if needed. Call off the main thread. */
    fun get(doc: Doc): File? {
        cached(doc)?.let { return it }
        if (doc.isAudio || doc.isDirectory) return null
        val bitmap = runCatching { if (doc.isVideo) videoFrame(doc) else photo(doc) }.getOrNull() ?: return null
        val out = fileFor(doc)
        val tmp = File(out.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        bitmap.recycle()
        tmp.renameTo(out)
        return out
    }

    private fun photo(doc: Doc): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        store.openInput(doc).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val bmp = store.openInput(doc).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = runCatching { store.openInput(doc).use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        return scaled(bmp, rotation)
    }

    private fun videoFrame(doc: Doc): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, store.uri(doc))
            r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2)
                ?.let { scaled(it, 0) }
        } finally {
            r.release()
        }
    }

    /** Scales so the short side is [SIZE] (thumbnails are shown cropped square) and applies rotation. */
    private fun scaled(src: Bitmap, rotation: Int): Bitmap {
        val scale = SIZE.toFloat() / minOf(src.width, src.height)
        if (scale >= 1f && rotation == 0) return src
        val m = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also { if (it !== src) src.recycle() }
    }

    /** Creates thumbnails for [docs] that don't have one yet. */
    fun warm(docs: List<Doc>) = docs.forEach { runCatching { get(it) } }

    companion object {
        const val SIZE = 320
    }
}

/** A thumbnail file for [doc]: immediately if cached, otherwise as soon as it's made. */
@Composable
fun rememberThumb(store: PhotoStore, doc: Doc?): State<File?> {
    val thumbs = store.thumbs
    return produceState(doc?.let { thumbs.cached(it) }, doc?.path, doc?.lastModified) {
        if (value == null && doc != null) value = withContext(thumbs.dispatcher) { thumbs.get(doc) }
    }
}
