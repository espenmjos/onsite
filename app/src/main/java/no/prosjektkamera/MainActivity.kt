package no.prosjektkamera

import android.content.ClipData
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

class MainActivity : ComponentActivity() {
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val shutter = HardwareShutter.listener
        if (shutter != null && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.repeatCount == 0) shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = PhotoStore(applicationContext)
        Coil.setImageLoader(
            ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
        )

        setContent {
            OnsiteTheme {
                if (store.treeUri == null) {
                    StorageAccessScreen(store)
                    return@OnsiteTheme
                }
                LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.onStart() } }

                // Navigation is a small hand-rolled back stack: current folder
                // (relative path) plus an optional overlay screen.
                var folderPath by rememberSaveable { mutableStateOf("") }
                var camera by rememberSaveable { mutableStateOf(false) }
                var viewerPhoto by rememberSaveable { mutableStateOf<String?>(null) }
                var settings by rememberSaveable { mutableStateOf(false) }
                var trash by rememberSaveable { mutableStateOf(false) }

                val folder = Doc(folderPath, true)

                when {
                    trash -> {
                        BackHandler { trash = false }
                        TrashScreen(store, onClose = { trash = false })
                    }
                    settings -> {
                        BackHandler { settings = false }
                        SettingsScreen(store, onClose = { settings = false })
                    }
                    camera -> {
                        BackHandler { camera = false }
                        CameraScreen(
                            store = store,
                            folder = folder,
                            onClose = { camera = false },
                            onOpenPhoto = { camera = false; viewerPhoto = it.path },
                        )
                    }
                    viewerPhoto != null -> {
                        BackHandler { viewerPhoto = null }
                        ViewerScreen(
                            store = store,
                            folder = folder,
                            initial = Doc(viewerPhoto!!, false),
                            onClose = { viewerPhoto = null },
                        )
                    }
                    else -> {
                        if (!folder.isRoot) {
                            BackHandler { folderPath = folder.parent.path }
                        }
                        FolderScreen(
                            store = store,
                            folder = folder,
                            onOpenFolder = { folderPath = it.path },
                            onUp = { folderPath = folder.parent.path },
                            onOpenCamera = { camera = true },
                            onOpenPhoto = { viewerPhoto = it.path },
                            onOpenSettings = { settings = true },
                            onOpenTrash = { trash = true },
                        )
                    }
                }
            }
        }
    }
}

/**
 * First run (or after access was lost): the user picks the storage folder once,
 * normally Pictures/Onsite, and Onsite keeps access to just that folder.
 */
@Composable
fun StorageAccessScreen(store: PhotoStore) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && !store.adoptTree(uri)) {
            Toast.makeText(context, R.string.folder_not_supported, Toast.LENGTH_LONG).show()
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.storage_access_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.storage_access_body), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { picker.launch(PhotoStore.suggestedStartUri()) }) {
                Text(stringResource(R.string.choose_folder))
            }
        }
    }
}

/** Shares an exported ZIP from the app cache. */
fun Context.shareZip(file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
    val intent = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, uri)
    startActivity(Intent.createChooser(intent, getString(R.string.share)))
}

/** Shares documents with other apps; works for one or many, with read access granted to the receiver. */
fun Context.shareDocs(store: PhotoStore, docs: List<Doc>) {
    if (docs.isEmpty()) return
    val uris = docs.map { store.uri(it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.setType(docs.map { it.mimeType }.distinct().singleOrNull() ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData makes the read grant cover every shared item, not just the first.
    intent.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    startActivity(Intent.createChooser(intent, getString(R.string.share)))
}
