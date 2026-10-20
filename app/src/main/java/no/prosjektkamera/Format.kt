package no.prosjektkamera

import android.content.Context
import android.media.MediaMetadataRetriever
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Context.locale: Locale get() = resources.configuration.locales[0]

/** Formats with the best pattern for the app's language, e.g. "3. okt. 2026" vs "Oct 3, 2026". */
private fun Context.format(skeleton: String, ms: Long): String =
    SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))

/** "today 14:05", "yesterday 09:12", "3 Oct 14:05", or the date with year if not this year. */
fun formatWhen(context: Context, ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val time = context.format("Hm", ms)
    val daysAgo = run {
        val a = then.clone() as Calendar
        val b = now.clone() as Calendar
        listOf(a, b).forEach {
            it.set(Calendar.HOUR_OF_DAY, 0); it.set(Calendar.MINUTE, 0)
            it.set(Calendar.SECOND, 0); it.set(Calendar.MILLISECOND, 0)
        }
        ((b.timeInMillis - a.timeInMillis) / 86_400_000L).toInt()
    }
    return when {
        daysAgo == 0 -> context.getString(R.string.today_at, time)
        daysAgo == 1 -> context.getString(R.string.yesterday_at, time)
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> context.format("dMMMHm", ms)
        else -> context.format("dMMMyyyy", ms)
    }
}

fun formatDate(context: Context, ms: Long): String = context.format("dMMMyyyy", ms)

fun formatTime(context: Context, ms: Long): String = context.format("Hm", ms)

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format("%.0f kB", bytes / 1024.0)
}

/** "12 photos · 2 videos · 1 voice note" (empty parts left out). */
fun formatCounts(context: Context, info: PhotoStore.FolderInfo): String {
    if (info.total == 0) return context.getString(R.string.empty)
    val res = context.resources
    return listOfNotNull(
        info.photos.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.n_photos, it, it) },
        info.videos.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.n_videos, it, it) },
        info.audio.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.n_voice_notes, it, it) },
    ).joinToString(" · ")
}

private val durationCache = HashMap<String, Long>()

/** Duration of a video/audio file, read off the main thread and cached. */
@Composable
fun rememberDuration(file: File): State<Long?> {
    val key = "${file.path}:${file.lastModified()}"
    return produceState(durationCache[key], key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().run {
                    try {
                        setDataSource(file.path)
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        release()
                    }
                }
            }.getOrNull()
        }?.also { synchronized(durationCache) { durationCache[key] = it } }
    }
}
