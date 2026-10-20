package no.prosjektkamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Exposure
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Lets the activity route hardware volume keys to the shutter while the camera is open. */
object HardwareShutter {
    var listener: (() -> Unit)? = null
}

private enum class Mode { PHOTO, VIDEO }

private val Accent = Color(0xFFFFC94D)
private val RecordRed = Color(0xFFE53935)

@Composable
fun CameraScreen(store: PhotoStore, folder: File, onClose: () -> Unit, onOpenPhoto: (File) -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermission(Manifest.permission.CAMERA)) }
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

@SuppressLint("MissingPermission") // audio is only enabled when RECORD_AUDIO is granted
@Composable
private fun CameraContent(store: PhotoStore, folder: File, onClose: () -> Unit, onOpenPhoto: (File) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppSettings.get(context) }
    val mode = Mode.valueOf(prefs.cameraMode)

    // CameraController gives us pinch-to-zoom, tap-to-focus and rotation handling for free.
    val controller = remember {
        LifecycleCameraController(context).apply {
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            bindToLifecycle(lifecycleOwner)
        }
    }

    // Push persisted settings into the controller whenever they change.
    LaunchedEffect(mode) {
        controller.setEnabledUseCases(
            if (mode == Mode.PHOTO) CameraController.IMAGE_CAPTURE else CameraController.VIDEO_CAPTURE
        )
    }
    LaunchedEffect(prefs.wide) {
        val selector = ResolutionSelector.Builder().setAspectRatioStrategy(
            if (prefs.wide) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        ).build()
        controller.previewResolutionSelector = selector
        controller.imageCaptureResolutionSelector = selector
    }
    LaunchedEffect(prefs.videoQuality) {
        val q = AppSettings.videoQualities.first { it.first == prefs.videoQuality }.second
        controller.videoCaptureQualitySelector = QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
    }
    LaunchedEffect(prefs.flash) { controller.imageCaptureFlashMode = prefs.flash }

    var torch by remember { mutableStateOf(false) }
    LaunchedEffect(torch, mode) { controller.enableTorch(torch && mode == Mode.VIDEO) }

    var backCamera by remember { mutableStateOf(true) }
    val zoom by controller.zoomState.observeAsState(lifecycleOwner)
    val focusState by controller.tapToFocusState.observeAsState(lifecycleOwner)
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    var showExposure by remember { mutableStateOf(false) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(store.media(folder).firstOrNull()) }
    var taken by remember { mutableIntStateOf(0) }
    val flashAlpha = remember { Animatable(0f) }

    var recording by remember { mutableStateOf<Recording?>(null) }
    var recordNanos by remember { mutableLongStateOf(0L) }

    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(mode) {
        if (mode == Mode.VIDEO && !context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun takePhoto() {
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
                    store.mediaSaved(file)
                }

                override fun onError(e: ImageCaptureException) {
                    busy = false
                    file.delete()
                    Toast.makeText(context, "Feil ved lagring: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    fun toggleRecording() {
        recording?.let { it.stop(); recording = null; return }
        val file = store.newVideoFile(folder)
        recordNanos = 0
        recording = controller.startRecording(
            FileOutputOptions.Builder(file).build(),
            AudioConfig.create(context.hasPermission(Manifest.permission.RECORD_AUDIO)),
            ContextCompat.getMainExecutor(context),
        ) { event ->
            when (event) {
                is VideoRecordEvent.Status -> recordNanos = event.recordingStats.recordedDurationNanos
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    // SOURCE_INACTIVE means the camera closed mid-recording; the file is still usable.
                    if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                        file.delete()
                        Toast.makeText(context, "Feil ved opptak: ${event.cause?.message}", Toast.LENGTH_LONG).show()
                    } else {
                        last = file
                        taken++
                        store.mediaSaved(file)
                    }
                }
            }
        }
    }

    fun shutter() = if (mode == Mode.PHOTO) takePhoto() else toggleRecording()

    DisposableEffect(mode, prefs.volumeShutter) {
        HardwareShutter.listener = if (prefs.volumeShutter) ::shutter else null
        onDispose { HardwareShutter.listener = null }
    }
    // Leaving the screen ends any recording so the file gets finalized.
    DisposableEffect(Unit) { onDispose { recording?.stop() } }

    val isRecording = recording != null

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    this.controller = controller
                    // Remember where the user tapped so we can draw the focus ring; returning
                    // false lets PreviewView still perform the actual focus/zoom handling.
                    setOnTouchListener { _, e ->
                        if (e.actionMasked == MotionEvent.ACTION_UP && e.pointerCount == 1) {
                            focusPoint = Offset(e.x, e.y)
                        }
                        false
                    }
                }
            },
        )
        if (prefs.grid) GridOverlay()
        FocusRing(focusPoint, focusState) { focusPoint = null }
        Box(Modifier.fillMaxSize().alpha(flashAlpha.value).background(Color.White))

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            // ---- Top bar ----
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, enabled = !isRecording) {
                    Icon(Icons.Default.Close, "Lukk", tint = Color.White)
                }
                if (isRecording) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                        RecordingBadge(recordNanos)
                    }
                } else {
                    FolderBadge(store.relativePath(folder).replace("/", " › "), Modifier.weight(1f))
                }
                if (mode == Mode.PHOTO) {
                    TopIcon(
                        when (prefs.flash) {
                            ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                            ImageCapture.FLASH_MODE_OFF -> Icons.Default.FlashOff
                            else -> Icons.Default.FlashAuto
                        },
                        "Blits", active = prefs.flash != ImageCapture.FLASH_MODE_OFF,
                    ) {
                        prefs.flash = when (prefs.flash) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                    }
                } else {
                    TopIcon(
                        if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                        "Lommelykt", active = torch,
                    ) { torch = !torch }
                }
                TopIcon(Icons.Default.Exposure, "Eksponering", active = showExposure || exposureIndex != 0) {
                    showExposure = !showExposure; showSettings = false
                }
                TopIcon(Icons.Default.Settings, "Innstillinger", active = showSettings, enabled = !isRecording) {
                    showSettings = !showSettings; showExposure = false
                }
            }

            if (showSettings) SettingsPanel(prefs)
            if (showExposure) ExposurePanel(controller, exposureIndex) { exposureIndex = it }

            Spacer(Modifier.weight(1f))

            // ---- Zoom presets ----
            zoom?.let { z -> ZoomBar(z.zoomRatio, z.minZoomRatio, z.maxZoomRatio) { controller.setZoomRatio(it) } }

            // ---- Mode switch ----
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).alpha(if (isRecording) 0f else 1f),
                horizontalArrangement = Arrangement.Center,
            ) {
                ModeLabel("FOTO", mode == Mode.PHOTO, enabled = !isRecording) { prefs.cameraMode = Mode.PHOTO.name }
                Spacer(Modifier.width(24.dp))
                ModeLabel("VIDEO", mode == Mode.VIDEO, enabled = !isRecording) { prefs.cameraMode = Mode.VIDEO.name }
            }

            // ---- Bottom bar: last capture, shutter, camera switch ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LastCaptureThumb(last, taken, enabled = !isRecording) { last?.let(onOpenPhoto) }
                ShutterButton(mode, isRecording, busy, ::shutter)
                IconButton(
                    enabled = !isRecording,
                    onClick = {
                        backCamera = !backCamera
                        exposureIndex = 0
                        controller.cameraSelector =
                            if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    },
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                ) {
                    Icon(Icons.Default.Cameraswitch, "Bytt kamera", tint = Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
private fun TopIcon(icon: ImageVector, desc: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(icon, desc, tint = if (active) Accent else Color.White)
    }
}

@Composable
private fun FolderBadge(path: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text(path, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecordingBadge(nanos: Long) {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { while (true) { delay(500); blink = !blink } }
    val s = nanos / 1_000_000_000
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(RecordRed.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).alpha(if (blink) 1f else 0.3f).clip(CircleShape).background(Color.White))
        Spacer(Modifier.width(8.dp))
        Text(
            String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60),
            color = Color.White, fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Accent else Color.White.copy(alpha = 0.8f),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = enabled, onClick = onClick)
            .background(if (selected) Color.Black.copy(alpha = 0.5f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun LastCaptureThumb(file: File?, taken: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
            .background(Color.DarkGray)
            .border(2.dp, Color.White, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled && file != null, onClick = onClick),
    ) {
        if (file != null) {
            AsyncImage(
                model = file, contentDescription = "Siste opptak",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            if (file.isVideo) {
                Icon(
                    Icons.Default.PlayCircle, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(24.dp),
                )
            }
        }
        if (taken > 0) {
            Text(
                "+$taken", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 6.dp))
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ShutterButton(mode: Mode, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(78.dp).clip(CircleShape)
            .border(4.dp, Color.White, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            mode == Mode.PHOTO -> Box(
                Modifier.size(62.dp).clip(CircleShape).background(if (busy) Color.LightGray else Color.White)
            )
            recording -> Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(RecordRed))
            else -> Box(Modifier.size(62.dp).clip(CircleShape).background(RecordRed))
        }
    }
}

@Composable
private fun ZoomBar(ratio: Float, min: Float, max: Float, onZoom: (Float) -> Unit) {
    val presets = buildList {
        if (min < 0.95f) add(min)
        addAll(listOf(1f, 2f, 3f, 5f, 10f).filter { it <= max })
    }
    // Highlight the highest preset at or below the current ratio; show the exact ratio on it.
    val active = presets.lastOrNull { it <= ratio + 0.05f } ?: presets.first()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.4f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { p ->
                val isActive = p == active
                Box(
                    Modifier.size(if (isActive) 44.dp else 36.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .clickable { onZoom(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isActive) fmtZoom(ratio) + "×" else fmtZoom(p),
                        color = if (isActive) Accent else Color.White,
                        fontSize = if (isActive) 13.sp else 11.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private fun fmtZoom(z: Float): String =
    if (abs(z - z.roundToInt()) < 0.05f) z.roundToInt().toString()
    else String.format(Locale.ROOT, "%.1f", z)

@Composable
private fun ExposurePanel(controller: LifecycleCameraController, index: Int, onChange: (Int) -> Unit) {
    val state = controller.cameraInfo?.exposureState
    Panel {
        if (state == null || !state.isExposureCompensationSupported) {
            Text("Eksponering støttes ikke på dette kameraet", color = Color.White)
            return@Panel
        }
        val range = state.exposureCompensationRange
        val ev = index * state.exposureCompensationStep.toFloat()
        fun set(i: Int) {
            onChange(i)
            controller.cameraControl?.setExposureCompensationIndex(i)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.ROOT, "%+.1f EV", ev), color = Color.White, modifier = Modifier.width(72.dp))
            Slider(
                value = index.toFloat(),
                onValueChange = { val i = it.roundToInt(); if (i != index) set(i) },
                valueRange = range.lower.toFloat()..range.upper.toFloat(),
                modifier = Modifier.weight(1f),
                colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
            )
            Text("Nullstill", color = Color.White, modifier = Modifier.clickable { set(0) }.padding(8.dp))
        }
    }
}

@Composable
private fun SettingsPanel(prefs: AppSettings) {
    Panel {
        SettingRow("Bildeformat") {
            Choice("4:3", !prefs.wide) { prefs.wide = false }
            Choice("16:9", prefs.wide) { prefs.wide = true }
        }
        SettingRow("Videokvalitet") {
            AppSettings.videoQualities.forEach { (name, _) ->
                Choice(name, prefs.videoQuality == name) { prefs.videoQuality = name }
            }
        }
        SettingRow("Rutenett") {
            Switch(checked = prefs.grid, onCheckedChange = { prefs.grid = it })
        }
        SettingRow("Volumknapper som utløser") {
            Switch(checked = prefs.volumeShutter, onCheckedChange = { prefs.volumeShutter = it })
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.7f))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = Accent,
            selectedLabelColor = Color.Black,
        ),
    )
}

@Composable
private fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.35f)
        for (i in 1..2) {
            val x = size.width * i / 3
            val y = size.height * i / 3
            drawLine(c, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun FocusRing(point: Offset?, state: Int?, onDone: () -> Unit) {
    if (point == null) return
    val focused = state == CameraController.TAP_TO_FOCUS_FOCUSED
    val done = focused || state == CameraController.TAP_TO_FOCUS_NOT_FOCUSED ||
        state == CameraController.TAP_TO_FOCUS_FAILED
    LaunchedEffect(point, done) {
        delay(if (done) 600 else 3000) // safety timeout in case no result arrives
        onDone()
    }
    val ring = 72.dp
    val half = with(LocalDensity.current) { (ring / 2).toPx() }
    Box(
        Modifier
            .offset { IntOffset((point.x - half).roundToInt(), (point.y - half).roundToInt()) }
            .size(ring)
            .border(2.dp, if (focused) Accent else Color.White, CircleShape)
    )
}

// ---------------------------------------------------------------- helpers

private fun Context.hasPermission(p: String) =
    ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun <T> LiveData<T>.observeAsState(owner: LifecycleOwner): State<T?> {
    val state = remember(this) { mutableStateOf(value) }
    DisposableEffect(this, owner) {
        val observer = Observer<T> { state.value = it }
        observe(owner, observer)
        onDispose { removeObserver(observer) }
    }
    return state
}
