package no.prosjektkamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import coil.Coil
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import java.io.File

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
            val scheme = if (isSystemInDarkTheme())
                darkColorScheme(primary = Color(0xFF9CC4F0), secondary = Color(0xFFFFC94D))
            else
                lightColorScheme(primary = Color(0xFF1F5C99), secondary = Color(0xFFB07D00))
            MaterialTheme(colorScheme = scheme) {
                var access by remember { mutableStateOf(hasStorageAccess(this)) }
                LifecycleResumeEffect(Unit) {
                    access = hasStorageAccess(this@MainActivity)
                    onPauseOrDispose {}
                }
                LaunchedEffect(access) { if (access) store.init() }
                if (!access) {
                    StorageAccessScreen()
                    return@MaterialTheme
                }

                // Navigation is a small hand-rolled back stack: current folder
                // (relative path) plus an optional overlay screen.
                var folderPath by rememberSaveable { mutableStateOf("") }
                var camera by rememberSaveable { mutableStateOf(false) }
                var viewerPhoto by rememberSaveable { mutableStateOf<String?>(null) }
                var settings by rememberSaveable { mutableStateOf(false) }

                val folder = File(store.root, folderPath).takeIf { it.isDirectory } ?: store.root

                when {
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
                            initial = File(viewerPhoto!!),
                            onClose = { viewerPhoto = null },
                        )
                    }
                    else -> {
                        if (folder != store.root) {
                            BackHandler { folderPath = store.relativePath(folder.parentFile!!) }
                        }
                        FolderScreen(
                            store = store,
                            folder = folder,
                            onOpenFolder = { folderPath = store.relativePath(it) },
                            onUp = { folderPath = store.relativePath(folder.parentFile!!) },
                            onOpenCamera = { camera = true },
                            onOpenPhoto = { viewerPhoto = it.path },
                            onOpenSettings = { settings = true },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageAccessScreen() {
    val context = LocalContext.current
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Tilgang til lagring", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            Text(
                "Bildene lagres i Pictures/Prosjektkamera, så de ligger trygt selv om appen " +
                    "avinstalleres. For å lage og flytte mapper der trenger appen «Tilgang til alle filer».",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                } else {
                    legacyLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }) { Text("Gi tilgang") }
        }
    }
}

fun Context.sharePhotos(files: List<File>) {
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(this, "$packageName.files", it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.setType(files.map { it.mimeType }.distinct().singleOrNull() ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "Del"))
}
