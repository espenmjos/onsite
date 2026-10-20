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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
) {
    val context = LocalContext.current
    val isRoot = folder == store.root
    val version = store.version.intValue
    val subfolders = remember(folder, version) { store.subfolders(folder) }
    val photos = remember(folder, version) { store.media(folder) }
    val counts = remember(folder, version) { subfolders.associateWith { store.countMediaRecursive(it) } }

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
                        IconButton(onClick = { dialog = FolderDialog.Create }) {
                            Icon(Icons.Default.CreateNewFolder, "Ny mappe")
                        }
                        if (isRoot) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, "Mer")
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    val visible = remember(version) { store.visibleInGallery }
                                    DropdownMenuItem(
                                        text = { Text("Vis i Galleri") },
                                        trailingIcon = { Checkbox(checked = visible, onCheckedChange = null) },
                                        onClick = { menuOpen = false; store.visibleInGallery = !visible })
                                }
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
                ExtendedFloatingActionButton(
                    onClick = { dialog = FolderDialog.Create },
                    icon = { Icon(Icons.Default.CreateNewFolder, null) },
                    text = { Text("Nytt prosjekt") })
            } else {
                ExtendedFloatingActionButton(
                    onClick = onOpenCamera,
                    icon = { Icon(Icons.Default.PhotoCamera, null) },
                    text = { Text("Kamera") })
            }
        },
    ) { padding ->
        if (subfolders.isEmpty() && photos.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (isRoot) "Ingen prosjekter ennå.\nTrykk «Nytt prosjekt» for å starte.\n\nBildene lagres i Pictures/Prosjektkamera."
                    else "Tom mappe.\nTa bilder og video her, eller lag undermapper med mappe-ikonet øverst.",
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
            items(subfolders, key = { "d:" + it.path }, span = { GridItemSpan(maxLineSpan) }) { dir ->
                ListItem(
                    modifier = Modifier.clickable(enabled = !selecting) { onOpenFolder(dir) },
                    leadingContent = {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                    },
                    headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val n = counts[dir] ?: 0
                        Text(if (n == 1) "1 fil" else "$n filer")
                    },
                )
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
                    AsyncImage(
                        model = photo,
                        contentDescription = photo.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().padding(if (isSel) 3.dp else 0.dp),
                    )
                    if (photo.isVideo) {
                        Icon(
                            Icons.Default.PlayCircle, "Video", tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(36.dp)
                        )
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
                text = "Mappen, alle undermapper og $n bilde(r)/video(er) slettes permanent.",
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

@Composable
private fun MoveDialog(store: PhotoStore, exclude: File, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val folders = remember { store.allFolders().filter { it != exclude } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flytt til mappe") },
        text = {
            if (folders.isEmpty()) {
                Text("Ingen andre mapper å flytte til.")
            } else {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(folders) { dir ->
                        val depth = store.relativePath(dir).count { it == '/' }
                        ListItem(
                            modifier = Modifier.clickable { onPick(dir) }.padding(start = (depth * 16).dp),
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            headlineContent = { Text(dir.name) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
