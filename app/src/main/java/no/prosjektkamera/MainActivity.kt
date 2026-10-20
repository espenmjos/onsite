package no.prosjektkamera

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = PhotoStore(applicationContext)

        setContent {
            val scheme = if (isSystemInDarkTheme())
                darkColorScheme(primary = Color(0xFF9CC4F0), secondary = Color(0xFFFFC94D))
            else
                lightColorScheme(primary = Color(0xFF1F5C99), secondary = Color(0xFFB07D00))
            MaterialTheme(colorScheme = scheme) {
                // Navigation is a small hand-rolled back stack: current folder
                // (relative path) plus an optional overlay screen.
                var folderPath by rememberSaveable { mutableStateOf("") }
                var camera by rememberSaveable { mutableStateOf(false) }
                var viewerPhoto by rememberSaveable { mutableStateOf<String?>(null) }

                val folder = File(store.root, folderPath).takeIf { it.isDirectory } ?: store.root

                when {
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
                        )
                    }
                }
            }
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
    }.setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, "Del bilder"))
}
