package no.prosjektkamera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import android.widget.Toast
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ViewerScreen(store: PhotoStore, folder: Doc, initial: Doc, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = store.version.intValue
    val loaded by produceState<List<Doc>?>(null, folder, version) {
        value = withContext(Dispatchers.IO) { store.media(folder) }
    }
    val photos = loaded
    if (photos == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = photos.indexOf(initial).coerceAtLeast(0)) { photos.size }
    val current = photos.getOrNull(pager.currentPage) ?: photos.last()
    var confirmDelete by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val note by produceState<String?>(null, current, version) {
        value = withContext(Dispatchers.IO) { store.note(current) }
    }
    // Tapping a photo hides/shows the bars, like the system gallery.
    var chrome by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { photos[it].path }) { page ->
            val file = photos[page]
            when {
                file.isVideo -> VideoPage(store.uri(file), isCurrent = page == pager.currentPage)
                file.isAudio -> AudioPage(store.uri(file), file.name, isCurrent = page == pager.currentPage)
                else -> ZoomableImage(store.uri(file), file.name, onTap = { chrome = !chrome })
            }
        }

        // ---- Top: back + date ----
        AnimatedVisibility(chrome || !current.isPhoto, enter = fadeIn(), exit = fadeOut()) {
          Column {
            Box(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(4.dp),
            ) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = Color.White)
                }
                IconButton(onClick = { showDetails = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Outlined.Info, stringResource(R.string.details), tint = Color.White)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatDate(context, current.lastModified),
                        color = Color.White, style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        formatTime(context, current.lastModified) + "  ·  " +
                            stringResource(R.string.position, pager.currentPage + 1, photos.size),
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // The note sits under the top bar so it never covers the video/audio controls at the bottom.
            note?.let {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
                        .clickable { editingNote = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Icon(
                        Icons.Default.EditNote, null, tint = SafetyYellow,
                        modifier = Modifier.padding(end = 10.dp).size(20.dp),
                    )
                    Text(
                        it, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 5, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
          }
        }

        // ---- Bottom: actions ----
        AnimatedVisibility(
            chrome || !current.isPhoto, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                BottomAction(Icons.Default.Share, stringResource(R.string.share)) { context.shareDocs(store, listOf(current)) }
                BottomAction(Icons.Default.EditNote, stringResource(R.string.note)) { editingNote = true }
                BottomAction(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.move_to)) { moving = true }
                BottomAction(Icons.Default.Delete, stringResource(R.string.delete)) { confirmDelete = true }
            }
        }
    }

    if (editingNote) NoteDialog(store, current) { editingNote = false }
    if (showDetails) DetailsSheet(store, current) { showDetails = false }

    if (moving) {
        MoveDialog(
            store = store,
            exclude = folder,
            onDismiss = { moving = false },
            onPick = { dest ->
                moving = false
                val item = current
                scope.launch {
                    withContext(Dispatchers.IO) { store.moveItems(listOf(item), dest) }
                    Toast.makeText(context, context.getString(R.string.moved_to, dest.name), Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = when {
                current.isVideo -> stringResource(R.string.delete_video_q)
                current.isAudio -> stringResource(R.string.delete_audio_q)
                else -> stringResource(R.string.delete_photo_q)
            },
            text = stringResource(R.string.file_deleted_permanently),
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                val item = current
                scope.launch {
                    withContext(Dispatchers.IO) { store.deleteItems(listOf(item)) }
                    Toast.makeText(context, R.string.moved_to_trash, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@Composable
private fun BottomAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ZoomableImage(uri: Uri, name: String, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = {
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
            model = uri,
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale; scaleY = scale
                translationX = offset.x; translationY = offset.y
            },
        )
    }
}

@Composable
private fun rememberPlayer(uri: Uri, isCurrent: Boolean, autoPlay: Boolean): ExoPlayer {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // Pause when swiped away; optionally start playing when this page becomes current.
    LaunchedEffect(isCurrent) { player.playWhenReady = isCurrent && autoPlay }
    return player
}

@Composable
private fun AudioPage(uri: Uri, name: String, isCurrent: Boolean) {
    val player = rememberPlayer(uri, isCurrent, autoPlay = false)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp, bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.GraphicEq, null, tint = Color.White, modifier = Modifier.size(120.dp))
        Text(stringResource(R.string.voice_note), color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            name, color = Color.White.copy(alpha = 0.6f),
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
private fun VideoPage(uri: Uri, isCurrent: Boolean) {
    val player = rememberPlayer(uri, isCurrent, autoPlay = true)
    AndroidView(
        // Keep the player's controls clear of the status bar/top bar and the system navigation bar.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(top = 56.dp, bottom = 64.dp),
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
