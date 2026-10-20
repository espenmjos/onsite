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
                    onDismiss = { finish() },
                    onPick = { dest ->
                        saving = true
                        settings.lastImportFolder = store.relativePath(dest)
                        lifecycleScope.launch {
                            val files = withContext(Dispatchers.IO) { uris.mapNotNull { copyIn(store, it, dest) } }
                            store.imported(files)
                            val saved = files.size
                            val msg = if (saved == uris.size) {
                                resources.getQuantityString(R.plurals.saved_n_to, saved, saved, dest.name)
                            } else {
                                getString(R.string.saved_some, saved, uris.size)
                            }
                            Toast.makeText(this@ShareReceiverActivity, msg, Toast.LENGTH_LONG).show()
                            finish()
                        }
                    },
                )
            }
        }
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
        contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        modified?.let { target.setLastModified(it) }
        target
    }.getOrNull()
}
