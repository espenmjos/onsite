package no.prosjektkamera

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.net.Uri
import android.view.View
import coil.compose.AsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ViewerScreen(store: PhotoStore, folder: File, initial: File, onClose: () -> Unit) {
    val context = LocalContext.current
    val version = store.version.intValue
    val photos = remember(folder, version) { store.media(folder) }
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = photos.indexOf(initial).coerceAtLeast(0)) { photos.size }
    val current = photos.getOrNull(pager.currentPage) ?: photos.last()
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { photos[it].path }) { page ->
            val file = photos[page]
            when {
                file.isVideo -> VideoPage(file, isCurrent = page == pager.currentPage)
                file.isAudio -> AudioPage(file, isCurrent = page == pager.currentPage)
                else -> ZoomableImage(file)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).safeDrawingPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake", tint = Color.White)
            }
            Text(
                SimpleDateFormat("d. MMM yyyy HH:mm", Locale("nb")).format(Date(current.lastModified())) +
                    "  ·  ${pager.currentPage + 1}/${photos.size}",
                color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { context.sharePhotos(listOf(current)) }) {
                Icon(Icons.Default.Share, "Del", tint = Color.White)
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, "Slett", tint = Color.White)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = when {
                current.isVideo -> "Slette videoen?"
                current.isAudio -> "Slette lydopptaket?"
                else -> "Slette bildet?"
            },
            text = "Filen slettes permanent.",
            onDismiss = { confirmDelete = false },
            onConfirm = { confirmDelete = false; store.deletePhotos(listOf(current)) },
        )
    }
}

@Composable
private fun ZoomableImage(file: File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                // Only consume gestures while zoomed or pinching, so the pager can still swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } > 1
                        if (pinching || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
private fun rememberPlayer(file: File, isCurrent: Boolean, autoPlay: Boolean): ExoPlayer {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Pause when swiped away; optionally start playing when this page becomes current.
    LaunchedEffect(isCurrent) { player.playWhenReady = isCurrent && autoPlay }
    return player
}

@Composable
private fun AudioPage(file: File, isCurrent: Boolean) {
    val player = rememberPlayer(file, isCurrent, autoPlay = false)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(120.dp))
        Text("Lydopptak", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            file.name, color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.weight(1f))
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    // Audio has no picture, so keep the controls permanently visible.
                    controllerShowTimeoutMs = 0
                    controllerHideOnTouch = false
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                    showController()
                }
            },
        )
    }
}

@Composable
private fun VideoPage(file: File, isCurrent: Boolean) {
    val player = rememberPlayer(file, isCurrent, autoPlay = true)
    AndroidView(
        // Keep the player's controls clear of the status bar/top bar and the system navigation bar.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowNextButton(false)
                setShowPreviousButton(false)
                // Speed/track settings aren't useful for job videos.
                findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            }
        },
    )
}
