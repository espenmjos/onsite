package no.prosjektkamera

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CameraScreen(store: PhotoStore, folder: File, onClose: () -> Unit, onOpenPhoto: (File) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent(store, folder, onClose, onOpenPhoto)
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Appen trenger tilgang til kameraet.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.size(16.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Gi tilgang") }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onClose) { Text("Tilbake") }
            }
        }
    }
}

@Composable
private fun CameraContent(store: PhotoStore, folder: File, onClose: () -> Unit, onOpenPhoto: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // CameraController gives us pinch-to-zoom, tap-to-focus and rotation handling for free.
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            bindToLifecycle(lifecycleOwner)
        }
    }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(store.photos(folder).firstOrNull()) }
    var taken by remember { mutableIntStateOf(0) }
    val flashAlpha = remember { Animatable(0f) }

    fun capture() {
        if (busy) return
        busy = true
        val file = store.newPhotoFile(folder)
        scope.launch {
            flashAlpha.snapTo(0.7f)
            flashAlpha.animateTo(0f, tween(250))
        }
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    busy = false
                    last = file
                    taken++
                    store.photoSaved(file)
                }

                override fun onError(e: ImageCaptureException) {
                    busy = false
                    file.delete()
                    Toast.makeText(context, "Feil ved lagring: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                this.controller = controller
            }
        },
    )
    Box(Modifier.fillMaxSize().alpha(flashAlpha.value).background(Color.White))

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Top bar: close, target folder, flash
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Lukk", tint = Color.White) }
            Row(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(
                    store.relativePath(folder).replace("/", " › "),
                    color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                    else -> ImageCapture.FLASH_MODE_AUTO
                }
                controller.imageCaptureFlashMode = flashMode
            }) {
                Icon(
                    when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff
                        else -> Icons.Default.FlashAuto
                    },
                    "Blits", tint = Color.White,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // Bottom bar: last photo, shutter, session counter
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .clickable(enabled = last != null) { last?.let(onOpenPhoto) },
            ) {
                last?.let {
                    AsyncImage(model = it, contentDescription = "Siste bilde",
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Box(
                Modifier.size(76.dp).clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp).clip(CircleShape)
                    .background(if (busy) Color.LightGray else Color.White)
                    .clickable { capture() },
            )
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (taken > 0) Text("+$taken", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
