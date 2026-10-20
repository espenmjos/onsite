package no.prosjektkamera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Keeps a fresh GPS/network fix while the camera is open, and a reverse-geocoded
 * street address for it (looked up in the background, so capturing never waits).
 */
class LocationTracker(context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

    @Volatile var location: Location? = null
        private set
    @Volatile private var addressFor: Location? = null
    @Volatile var address: String? = null
        private set

    private val listener = LocationListener { loc ->
        if (location.let { it == null || loc.accuracy <= it.accuracy + 20 || loc.time - it.time > 30_000 }) {
            location = loc
        }
    }

    @SuppressLint("MissingPermission") // only started after the permission is granted
    fun start() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        location = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        providers.forEach {
            runCatching { manager.requestLocationUpdates(it, 3_000L, 5f, listener, Looper.getMainLooper()) }
        }
    }

    fun stop() = manager.removeUpdates(listener)

    /** Refreshes [address] if we've moved more than ~30 m since the last lookup. */
    suspend fun refreshAddress() {
        val loc = location ?: return
        if (geocoder == null) return
        addressFor?.let { if (it.distanceTo(loc) < 30) return }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            }.getOrNull()
        } ?: return
        address = listOfNotNull(
            listOfNotNull(found.thoroughfare, found.subThoroughfare).joinToString(" ").ifBlank { null },
            listOfNotNull(found.postalCode, found.locality).joinToString(" ").ifBlank { null },
        ).joinToString(", ").ifBlank { found.getAddressLine(0) }
        addressFor = loc
    }

    /** Address if we have one for roughly this spot, else coordinates. */
    fun describe(): String? {
        val loc = location ?: return null
        val coords = String.format(Locale.ROOT, "%.5f, %.5f", loc.latitude, loc.longitude)
        val near = addressFor?.let { it.distanceTo(loc) < 60 } == true
        return if (near && address != null) "$address  ($coords)" else coords
    }
}

/** Burns a text stamp into the bottom-left corner of a JPEG, keeping its EXIF data. */
object Stamper {
    private val keptTags = listOf(
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
    )

    fun stamp(file: File, lines: List<String>) {
        if (lines.isEmpty()) return
        val exif = ExifInterface(file)
        val rotation = exif.rotationDegrees
        val src = BitmapFactory.decodeFile(file.path) ?: return

        // Bake the EXIF rotation into the pixels so the stamp ends up upright.
        val swap = rotation == 90 || rotation == 270
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(src, Matrix().apply {
            postRotate(rotation.toFloat())
            when (rotation) {
                90 -> postTranslate(src.height.toFloat(), 0f)
                180 -> postTranslate(src.width.toFloat(), src.height.toFloat())
                270 -> postTranslate(0f, src.width.toFloat())
            }
        }, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()

        drawStamp(canvas, w, h, lines)

        val tmp = File(file.parentFile, ".${file.name}.tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()

        val newExif = ExifInterface(tmp)
        keptTags.forEach { tag -> exif.getAttribute(tag)?.let { newExif.setAttribute(tag, it) } }
        newExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        newExif.saveAttributes()
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }

    private fun drawStamp(canvas: Canvas, w: Int, h: Int, lines: List<String>) {
        val short = minOf(w, h).toFloat()
        val textSize = short * 0.032f
        val pad = textSize * 0.6f
        val lineGap = textSize * 0.3f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxText = w * 0.9f - 3 * pad
        // Shrink long lines (e.g. deep folder paths) to fit within the photo.
        val fitted = lines.map { line ->
            var t = line
            while (paint.measureText(t) > maxText && t.length > 4) t = "…" + t.drop(2)
            t
        }
        val textW = fitted.maxOf { paint.measureText(it) }
        val boxW = textW + pad * 3
        val boxH = fitted.size * textSize + (fitted.size - 1) * lineGap + pad * 2
        val margin = short * 0.025f
        val box = RectF(margin, h - margin - boxH, margin + boxW, h - margin)

        canvas.drawRoundRect(box, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(150, 0, 0, 0)
        })
        // Brand accent bar in Onsite yellow.
        canvas.drawRect(
            RectF(box.left, box.top + pad * 0.6f, box.left + pad * 0.45f, box.bottom - pad * 0.6f),
            Paint().apply { color = 0xFFFFC400.toInt() },
        )
        var y = box.top + pad + textSize * 0.85f
        fitted.forEach { line ->
            canvas.drawText(line, box.left + pad * 1.5f, y, paint)
            y += textSize + lineGap
        }
    }
}
