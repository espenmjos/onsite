package no.prosjektkamera

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data object Rename : FolderDialog
    data object DeleteFolder : FolderDialog
    data object DeletePhotos : FolderDialog
    data object Move : FolderDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FolderScreen(
    store: PhotoStore,
    folder: File,
    onOpenFolder: (File) -> Unit,
    onUp: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhoto: (File) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val isRoot = folder == store.root
    val version = store.version.intValue
    val settings = remember { AppSettings.get(context) }
    val infos = remember(folder, version) { store.subfolders(folder).associateWith { store.folderInfo(it) } }
    val subfolders = remember(infos, settings.sortByRecent) {
        if (settings.sortByRecent) infos.keys.sortedByDescending { infos.getValue(it).lastActivity }
        else infos.keys.toList()
    }
    val photos = remember(folder, version) { store.media(folder) }
    val folderInfo = remember(folder, version) { if (isRoot) null else store.folderInfo(folder) }
    var recordingAudio by remember { mutableStateOf(false) }

    var selected by remember(folder) { mutableStateOf(setOf<File>()) }
    val selecting = selected.isNotEmpty()
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = if (selecting) TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) else TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    when {
                        selecting -> IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, "Avbryt valg")
                        }
                        !isRoot -> IconButton(onClick = onUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Opp")
                        }
                    }
                },
                title = {
                    if (selecting) {
                        Text("${selected.size} valgt")
                    } else {
                        Column {
                            Text(
                                if (isRoot) "Prosjekter" else folder.name,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            val parent = folder.parentFile?.let { store.relativePath(it) }
                            if (!isRoot && !parent.isNullOrEmpty()) {
                                Text(
                                    parent.replace("/", " › "),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { context.sharePhotos(selected.toList()) }) {
                            Icon(Icons.Default.Share, "Del")
                        }
                        IconButton(onClick = { dialog = FolderDialog.Move }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Flytt")
                        }
                        IconButton(onClick = { dialog = FolderDialog.DeletePhotos }) {
                            Icon(Icons.Default.Delete, "Slett")
                        }
                    } else {
                        // At the root, new projects are created with the floating button instead.
                        if (!isRoot) {
                            IconButton(onClick = { dialog = FolderDialog.Create }) {
                                Icon(Icons.Default.CreateNewFolder, "Ny mappe")
                            }
                        }
                        if (isRoot) {
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, "Innstillinger")
                            }
                        } else {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, "Mer")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Del alt i denne mappen") },
                                        enabled = photos.isNotEmpty(),
                                        onClick = { menuOpen = false; context.sharePhotos(photos) })
                                    DropdownMenuItem(
                                        text = { Text("Gi nytt navn") },
                                        onClick = { menuOpen = false; dialog = FolderDialog.Rename })
                                    DropdownMenuItem(
                                        text = { Text("Slett mappe") },
                                        onClick = { menuOpen = false; dialog = FolderDialog.DeleteFolder })
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (selecting) return@Scaffold
            if (isRoot) {
                FloatingActionButton(onClick = { dialog = FolderDialog.Create }) {
                    Icon(Icons.Default.CreateNewFolder, "Nytt prosjekt")
                }
            } else {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(onClick = { recordingAudio = true }) {
                        Icon(Icons.Default.Mic, "Lydopptak")
                    }
                    FloatingActionButton(onClick = onOpenCamera) {
                        Icon(Icons.Default.PhotoCamera, "Kamera")
                    }
                }
            }
        },
    ) { padding ->
        if (isRoot && subfolders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Ingen prosjekter ennå.\nTrykk på mappeknappen nede til høyre for å starte.\n\nFilene lagres i Pictures/Prosjektkamera.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
                start = 4.dp, end = 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            folderInfo?.let { info ->
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) { FolderHeader(info) }
            }
            items(subfolders, key = { "d:" + it.path }, span = { GridItemSpan(maxLineSpan) }) { dir ->
                FolderRow(dir, infos.getValue(dir), enabled = !selecting) { onOpenFolder(dir) }
            }
            if (!isRoot && subfolders.isEmpty() && photos.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Tom mappe.\nTa bilder, video eller lydopptak her, eller lag undermapper med mappe-ikonet øverst.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            items(photos, key = { "f:" + it.path }) { photo ->
                val isSel = photo in selected
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSel) selected - photo else selected + photo
                                else onOpenPhoto(photo)
                            },
                            onLongClick = { selected = selected + photo },
                        )
                ) {
                    if (photo.isAudio) {
                        Column(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.tertiaryContainer),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.GraphicEq, "Lydopptak",
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                formatWhen(photo.lastModified()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    } else {
                        AsyncImage(
                            model = photo,
                            contentDescription = photo.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().padding(if (isSel) 3.dp else 0.dp),
                        )
                    }
                    if (photo.isVideo) {
                        Icon(
                            Icons.Default.PlayCircle, "Video", tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(36.dp)
                        )
                    }
                    if (photo.isVideo || photo.isAudio) {
                        val duration by rememberDuration(photo)
                        duration?.let {
                            Text(
                                formatDuration(it),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    if (isSel) {
                        Icon(
                            Icons.Default.CheckCircle, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraLarge)
                        )
                    }
                }
            }
        }
    }

    if (recordingAudio) AudioRecordDialog(store, folder) { recordingAudio = false }

    when (dialog) {
        FolderDialog.Create -> NameDialog(
            title = if (isRoot) "Nytt prosjekt" else "Ny undermappe i «${folder.name}»",
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                store.createFolder(folder, name)
                    .onSuccess { dialog = null; onOpenFolder(it) }
                    .onFailure { toast(it.message ?: "Feil") }
            },
        )
        FolderDialog.Rename -> NameDialog(
            title = "Gi nytt navn",
            initial = folder.name,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                store.renameFolder(folder, name)
                    .onSuccess { dialog = null; onOpenFolder(it) }
                    .onFailure { toast(it.message ?: "Feil") }
            },
        )
        FolderDialog.DeleteFolder -> {
            val n = store.countMediaRecursive(folder)
            ConfirmDialog(
                title = "Slette «${folder.name}»?",
                text = "Mappen, alle undermapper og $n fil(er) slettes permanent.",
                onDismiss = { dialog = null },
                onConfirm = { dialog = null; onUp(); store.deleteFolder(folder) },
            )
        }
        FolderDialog.DeletePhotos -> ConfirmDialog(
            title = "Slette ${selected.size} fil(er)?",
            text = "Filene slettes permanent.",
            onDismiss = { dialog = null },
            onConfirm = { store.deletePhotos(selected); selected = emptySet(); dialog = null },
        )
        FolderDialog.Move -> MoveDialog(
            store = store,
            exclude = folder,
            onDismiss = { dialog = null },
            onPick = { dest ->
                val n = selected.size
                store.movePhotos(selected, dest)
                selected = emptySet(); dialog = null
                toast("Flyttet $n fil(er) til ${dest.name}")
            },
        )
        null -> {}
    }
}

@Composable
private fun FolderHeader(info: PhotoStore.FolderInfo) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(formatCounts(info), style = MaterialTheme.typography.bodyMedium)
        Text(
            "Opprettet ${formatDate(info.created)}" +
                (info.lastAdded?.let { " · Sist lagt til ${formatWhen(it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderRow(dir: File, info: PhotoStore.FolderInfo, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        leadingContent = {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (info.cover != null) {
                    AsyncImage(
                        model = info.cover, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
        },
        headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(formatCounts(info), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    info.lastAdded?.let { "Sist lagt til ${formatWhen(it)}" }
                        ?: "Opprettet ${formatDate(info.created)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                singleLine = true, label = { Text("Navn") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Slett", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
