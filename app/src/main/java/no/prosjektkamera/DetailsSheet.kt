package no.prosjektkamera

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** What we can read back out of a photo, video or audio file. */
private data class MediaDetails(
    val taken: Long?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val camera: String?,
    val lat: Double?,
    val lon: Double?,
)

private fun readDetails(file: File): MediaDetails = runCatching {
    if (file.isPhoto) {
        val exif = ExifInterface(file)
        val taken = (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME))
            ?.let { runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(it)?.time }.getOrNull() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            .also { BitmapFactory.decodeFile(file.path, it) }
        val swap = exif.rotationDegrees == 90 || exif.rotationDegrees == 270
        val camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
            .distinct().joinToString(" ").ifBlank { null }
        val ll = exif.latLong
        MediaDetails(
            taken, if (swap) bounds.outHeight else bounds.outWidth, if (swap) bounds.outWidth else bounds.outHeight,
            null, camera, ll?.get(0), ll?.get(1),
        )
    } else {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            fun key(k: Int) = r.extractMetadata(k)
            val rot = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            // ISO 6709, e.g. "+59.9319+010.7601/"
            val loc = key(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?.let { Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(it) }
            val taken = key(MediaMetadataRetriever.METADATA_KEY_DATE)?.let {
                runCatching { SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.ROOT)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(it)?.time }.getOrNull()
            }?.takeIf { it > 946_684_800_000L } // ignore bogus 1904/1970 dates
            MediaDetails(
                taken,
                if (rot % 180 == 90) h else w, if (rot % 180 == 90) w else h,
                key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                null,
                loc?.groupValues?.get(1)?.toDoubleOrNull(), loc?.groupValues?.get(2)?.toDoubleOrNull(),
            )
        } finally {
            r.release()
        }
    }
}.getOrDefault(MediaDetails(null, null, null, null, null, null, null))

private suspend fun lookUpAddress(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
    if (!Geocoder.isPresent()) return@withContext null
    runCatching {
        @Suppress("DEPRECATION")
        Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.getAddressLine(0)
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(store: PhotoStore, file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<MediaDetails?>(null, file) { value = withContext(Dispatchers.IO) { readDetails(file) } }
    val d = details
    val address by produceState<String?>(null, d?.lat, d?.lon) {
        if (d?.lat != null && d.lon != null) value = lookUpAddress(context, d.lat, d.lon)
    }
    val version = store.version.intValue
    val custom = remember(file, version) { store.customAddress(file) }
    var editing by remember { mutableStateOf(false) }
    val hasGps = d?.lat != null && d.lon != null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
        ) {
            Text(stringResource(R.string.details), style = MaterialTheme.typography.titleLarge)
            if (d == null) return@Column

            DetailRow(Icons.Default.CalendarMonth, stringResource(R.string.taken),
                formatDateTime(context, d.taken ?: file.lastModified()))

            val coords = if (hasGps) String.format(Locale.ROOT, "%.5f, %.5f", d.lat, d.lon) else null
            DetailRow(
                Icons.Default.Place, stringResource(R.string.gps_position),
                when {
                    custom != null -> custom + "\n" + stringResource(R.string.edited_manually)
                    coords != null -> (address ?: stringResource(R.string.looking_up_address)) + "\n" + coords
                    else -> stringResource(R.string.no_location)
                },
                trailing = {
                    IconButton(onClick = { editing = true }) {
                        Icon(
                            Icons.Default.Edit,
                            stringResource(if (custom == null && !hasGps) R.string.add_address else R.string.edit_address),
                        )
                    }
                },
            )
            if (custom != null || hasGps) {
                OutlinedButton(
                    onClick = {
                        // A corrected address wins over the raw GPS position.
                        val geo = if (custom != null) Uri.parse("geo:0,0?q=" + Uri.encode(custom))
                        else Uri.parse("geo:${d.lat},${d.lon}?q=${d.lat},${d.lon}")
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, geo)) }
                    },
                    modifier = Modifier.padding(start = 40.dp, top = 4.dp),
                ) {
                    Icon(Icons.Default.Map, null, Modifier.size(18.dp))
                    Text("  " + stringResource(R.string.open_in_maps))
                }
            }

            DetailRow(Icons.Default.Folder, stringResource(R.string.folder),
                store.relativePath(file.parentFile!!).replace("/", " › "))
            DetailRow(Icons.Default.InsertDriveFile, stringResource(R.string.file),
                file.name + "\n" + formatSize(file.length()))
            if (d.width != null && d.height != null && d.width > 0) {
                DetailRow(Icons.Default.PhotoSizeSelectLarge, stringResource(R.string.resolution),
                    String.format(Locale.ROOT, "%d × %d  (%.1f MP)", d.width, d.height, d.width * d.height / 1e6))
            }
            d.durationMs?.let { DetailRow(Icons.Default.Timer, stringResource(R.string.length), formatDuration(it)) }
            d.camera?.let { DetailRow(Icons.Default.CameraAlt, stringResource(R.string.camera), it) }
        }
    }

    if (editing) {
        AddressDialog(
            initial = custom ?: address.orEmpty(),
            canReset = custom != null && hasGps,
            onDismiss = { editing = false },
            onSave = { store.setCustomAddress(file, it); editing = false },
        )
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 16.dp, top = 2.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        trailing?.invoke()
    }
}

@Composable
private fun AddressDialog(initial: String, canReset: Boolean, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_address)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(stringResource(R.string.address_hint)) },
                minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (canReset) TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.reset_to_gps)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
