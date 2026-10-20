package no.prosjektkamera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Receives photos/videos/audio shared from other apps ("Share → Onsite"), lets the
 * user pick a project folder, and copies the files in. Translucent, so the picker
 * floats over the app the user shared from.
 */
class ShareReceiverActivity : ComponentActivity() {
    /** Message shown once the originals have (or haven't) been moved to the system trash. */
    private var pendingMessage: String? = null

    private val trashRequest = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val msg = if (r.resultCode == RESULT_OK) pendingMessage else getString(R.string.originals_kept)
        msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = sharedUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        val store = PhotoStore(applicationContext)
        val settings = AppSettings.get(this)

        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) OnsiteDark else OnsiteLight) {
                if (!hasStorageAccess(this)) {
                    StorageAccessScreen()
                    return@MaterialTheme
                }
                var saving by remember { mutableStateOf(false) }
                if (saving) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        title = { Text(pluralStringResource(R.plurals.saving_n, uris.size, uris.size)) },
                        text = {
                            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(32.dp))
                            }
                        },
                    )
                    return@MaterialTheme
                }
                MoveDialog(
                    store = store,
                    exclude = store.root,
                    title = pluralStringResource(R.plurals.save_n_to_onsite, uris.size, uris.size),
                    confirmLabel = stringResource(R.string.save_here),
                    start = File(store.root, settings.lastImportFolder),
                    extra = {
                        Row(
                            Modifier.fillMaxWidth().clickable { settings.importMove = !settings.importMove }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.remove_originals), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(R.string.remove_originals_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = settings.importMove, onCheckedChange = { settings.importMove = it })
                        }
                    },
                    onDismiss = { finish() },
                    onPick = { dest ->
                        saving = true
                        settings.lastImportFolder = store.relativePath(dest)
                        val move = settings.importMove
                        lifecycleScope.launch {
                            val copied = withContext(Dispatchers.IO) {
                                uris.mapNotNull { uri -> copyIn(store, uri, dest)?.let { uri to it } }
                            }
                            store.imported(copied.map { it.second })
                            val saved = copied.size
                            val msg = when {
                                saved < uris.size -> getString(R.string.saved_some, saved, uris.size)
                                move -> resources.getQuantityString(R.plurals.moved_n_to, saved, saved, dest.name)
                                else -> resources.getQuantityString(R.plurals.saved_n_to, saved, saved, dest.name)
                            }
                            // Only originals whose copy succeeded are ever removed.
                            if (move && copied.isNotEmpty() && trashOriginals(copied.map { it.first }, msg)) return@launch
                            Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    },
                )
            }
        }
    }

    /**
     * Moves the shared originals to the system trash (recoverable for ~30 days).
     * Android asks the user to confirm. Returns false if nothing could be requested,
     * in which case the caller finishes normally and the originals stay.
     */
    private suspend fun trashOriginals(shared: List<Uri>, message: String): Boolean {
        val mediaUris = withContext(Dispatchers.IO) { shared.mapNotNull { toMediaStoreUri(it) } }
        if (mediaUris.isEmpty()) {
            Toast.makeText(this, getString(R.string.originals_kept), Toast.LENGTH_LONG).show()
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingMessage = message
            val request = MediaStore.createTrashRequest(contentResolver, mediaUris, true)
            trashRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
            return true
        }
        // Android 10 and older: delete directly (allowed by the legacy storage permission).
        withContext(Dispatchers.IO) { mediaUris.forEach { runCatching { contentResolver.delete(it, null, null) } } }
        return false
    }

    /**
     * Finds the MediaStore entry behind a shared URI. Camera and gallery apps usually
     * share MediaStore URIs directly; otherwise we look it up by its file path.
     */
    private fun toMediaStoreUri(uri: Uri): Uri? {
        if (uri.authority == MediaStore.AUTHORITY) return uri
        val path = runCatching {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: return null
        val files = MediaStore.Files.getContentUri("external")
        return contentResolver.query(
            files, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(files, c.getLong(0)) else null }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }

    /** Copies one shared item into [dest], keeping its name and (if known) its original date. */
    private fun copyIn(store: PhotoStore, uri: Uri, dest: File): File? = runCatching {
        var name: String? = null
        var modified: Long? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                // Prefer when the photo was taken; fall back to last modified (seconds).
                c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it >= 0 }
                    ?.let { i -> c.getLong(i).takeIf { it > 0 } }?.let { modified = it }
                if (modified == null) {
                    c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED).takeIf { it >= 0 }
                        ?.let { i -> c.getLong(i).takeIf { it > 0 } }?.let { modified = it * 1000 }
                }
            }
        }
        val ext = contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
        val target = store.newImportFile(dest, name ?: uri.lastPathSegment ?: "", ext)
        val bytes = contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        // Verify the copy before it can ever replace the original.
        val expected = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
        if (bytes == 0L || target.length() != bytes || (expected != null && expected != bytes)) {
            target.delete()
            return@runCatching null
        }
        modified?.let { target.setLastModified(it) }
        target
    }.getOrNull()
}
