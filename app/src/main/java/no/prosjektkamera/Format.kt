package no.prosjektkamera

import android.media.MediaMetadataRetriever
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

private val nb = Locale("nb")

/** "i dag 14:05", "i går 09:12", "3. okt 14:05", or with year if not this year. */
fun formatWhen(ms: Long): String {
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val time = SimpleDateFormat("HH:mm", nb).format(Date(ms))
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
        daysAgo == 0 -> "i dag $time"
        daysAgo == 1 -> "i går $time"
        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            SimpleDateFormat("d. MMM HH:mm", nb).format(Date(ms))
        else -> SimpleDateFormat("d. MMM yyyy", nb).format(Date(ms))
    }
}

fun formatDate(ms: Long): String = SimpleDateFormat("d. MMM yyyy", nb).format(Date(ms))

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(nb, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(nb, "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(nb, "%.0f kB", bytes / 1024.0)
}

/** "12 bilder · 2 videoer · 1 lydopptak" (empty parts left out). */
fun formatCounts(info: PhotoStore.FolderInfo): String {
    if (info.total == 0) return "Tom"
    return listOfNotNull(
        info.photos.takeIf { it > 0 }?.let { if (it == 1) "1 bilde" else "$it bilder" },
        info.videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videoer" },
        info.audio.takeIf { it > 0 }?.let { "$it lydopptak" },
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
