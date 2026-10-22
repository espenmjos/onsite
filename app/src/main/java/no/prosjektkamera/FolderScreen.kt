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
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.SelectAll
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope

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
    folder: Doc,
    onOpenFolder: (Doc) -> Unit,
    onUp: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenPhoto: (Doc) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
) {
    val context = LocalContext.current
    val isRoot = folder.isRoot
    val version = store.version.intValue
    val settings = remember { AppSettings.get(context) }
    val scope = rememberCoroutineScope()
    // Show the last known content instantly, then refresh from storage in the background
    // and warm up the subfolders so opening them is instant too.
    var content by remember(folder) { mutableStateOf(store.cachedFolder(folder)) }
    LaunchedEffect(folder, version) {
        val fresh = withContext(Dispatchers.IO) { store.loadFolder(folder) }
        content = fresh
        withContext(Dispatchers.IO) {
            store.warmThumbnails(fresh)
            store.prefetch(fresh.subfolders)
        }
    }
    val infos = content?.infos.orEmpty()
    val subfolders = content?.subfolders.orEmpty().let { subs ->
        if (settings.sortByRecent) subs.sortedByDescending { infos[it]?.lastActivity ?: 0L } else subs
    }
    val photos = content?.media.orEmpty()
    val folderInfo = content?.info
    val noted = content?.noted.orEmpty()
    val folderNote = content?.note
    var recordingAudio by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<Doc?>(null) }

    /** Runs a storage operation in the background, then [after] on the main thread. */
    fun <T> work(action: () -> T, after: (T) -> Unit = {}) = scope.launch {
        after(withContext(Dispatchers.IO) { action() })
    }

    var selected by remember(folder) { mutableStateOf(setOf<Doc>()) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // ---- Export (to a picked folder, or as a ZIP) ----
    val exporter = remember { Exporter(context.applicationContext, store) }
    var exportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingExport by remember { mutableStateOf<List<Doc>>(emptyList()) }
    var selectionMenuOpen by remember { mutableStateOf(false) }

    fun zipAndShare(items: List<Doc>, name: String) = scope.launch {
        exportProgress = 0 to 0
        val zip = withContext(Dispatchers.IO) {
            val total = exporter.count(items)
            exportProgress = 0 to total
            runCatching { exporter.zip(items, name) { done -> exportProgress = done to total } }.getOrNull()
        }
        exportProgress = null
        if (zip == null) toast(context.getString(R.string.error)) else context.shareZip(zip)
    }

    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val items = pendingExport
        if (tree == null || items.isEmpty()) return@rememberLauncherForActivityResult
        if (store.isInsideStorage(tree)) {
            toast(context.getString(R.string.export_inside_onsite))
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            exportProgress = 0 to 0
            val result = withContext(Dispatchers.IO) {
                val total = exporter.count(items)
                exportProgress = 0 to total
                runCatching { exporter.exportToTree(tree, items) { done -> exportProgress = done to total } }
            }
            exportProgress = null
            result.onSuccess { n -> toast(context.resources.getQuantityString(R.plurals.exported_n, n, n)) }
                .onFailure { toast(context.getString(R.string.export_failed)) }
        }
    }

    fun exportToFolder(items: List<Doc>) {
        pendingExport = items
        exportPicker.launch(PhotoStore.exportStartUri())
    }

    /** Name for a ZIP of [items]: the folder's own name if it's a single folder, else the current folder. */
    fun zipName(items: List<Doc>) = items.singleOrNull()?.takeIf { it.isDirectory }?.name
        ?: folder.name.ifEmpty { "Onsite" }
    val selecting = selected.isNotEmpty()
    var dialog by remember { mutableStateOf<FolderDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
          Column {
            TopAppBar(
                colors = if (selecting) TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) else TopAppBarDefaults.topAppBarColors(),
                navigationIcon = {
                    when {
                        selecting -> IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel_selection))
                        }
                        !isRoot -> IconButton(onClick = onUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.up))
                        }
                    }
                },
                title = {
                    if (selecting) {
                        Text(pluralStringResource(R.plurals.n_selected, selected.size, selected.size))
                    } else {
                        val title = if (isRoot) stringResource(R.string.projects) else folder.name
                        // Long project names (order number + customer + site) get two lines and a smaller size.
                        Text(
                            title,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = if (title.length > 22) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selected = (subfolders + photos).toSet() }) {
                            Icon(Icons.Default.SelectAll, stringResource(R.string.select_all))
                        }
                        IconButton(onClick = {
                            work({ store.mediaIn(selected) }) { files ->
                                if (files.isEmpty()) toast(context.getString(R.string.nothing_to_share))
                                else context.shareDocs(store, files)
                            }
                        }) {
                            Icon(Icons.Default.Share, stringResource(R.string.share))
                        }
                        IconButton(onClick = { dialog = FolderDialog.Move }) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.move))
                        }
                        IconButton(onClick = { dialog = FolderDialog.DeletePhotos }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                        Box {
                            IconButton(onClick = { selectionMenuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = selectionMenuOpen, onDismissRequest = { selectionMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_to_folder)) },
                                    onClick = { selectionMenuOpen = false; exportToFolder(selected.toList()) })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.share_zip)) },
                                    onClick = {
                                        selectionMenuOpen = false
                                        selected.toList().let { zipAndShare(it, zipName(it)) }
                                    })
                            }
                        }
                    } else {
                        // At the root, new projects are created with the floating button instead.
                        if (!isRoot) {
                            IconButton(onClick = { dialog = FolderDialog.Create }) {
                                Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder))
                            }
                        }
                        if (isRoot) {
                            IconButton(onClick = onOpenTrash) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.trash))
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Default.Settings, stringResource(R.string.settings))
                            }
                        } else {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.share_all_here)) },
                                        enabled = photos.isNotEmpty(),
                                        onClick = { menuOpen = false; context.shareDocs(store, photos) })
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.export_to_folder)) },
                                        onClick = { menuOpen = false; exportToFolder(listOf(folder)) })
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.share_zip)) },
                                        onClick = { menuOpen = false; zipAndShare(listOf(folder), folder.name) })
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.rename)) },
                                        onClick = { menuOpen = false; dialog = FolderDialog.Rename })
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete_folder)) },
                                        onClick = { menuOpen = false; dialog = FolderDialog.DeleteFolder })
                                }
                            }
                        }
                    }
                },
            )
          }
        },
        floatingActionButton = {
            if (selecting) return@Scaffold
            if (isRoot) {
                FloatingActionButton(onClick = { dialog = FolderDialog.Create }) {
                    Icon(Icons.Default.CreateNewFolder, stringResource(R.string.new_project))
                }
            } else {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FloatingActionButton(onClick = { recordingAudio = true }) {
                        Icon(Icons.Default.Mic, stringResource(R.string.voice_note))
                    }
                    FloatingActionButton(onClick = onOpenCamera) {
                        Icon(Icons.Default.PhotoCamera, stringResource(R.string.camera))
                    }
                }
            }
        },
    ) { padding ->
        if (content != null && isRoot && subfolders.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.empty_root),
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
            // The path scrolls away with the content instead of covering the photos.
            if (!isRoot) {
                item(key = "crumb", span = { GridItemSpan(maxLineSpan) }) { Breadcrumb(folder, onNavigate = onOpenFolder) }
            }
            folderInfo?.let { info ->
                item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                    FolderHeader(info, folderNote) { noteFor = folder }
                }
            }
            items(subfolders, key = { "d:" + it.path }, span = { GridItemSpan(maxLineSpan) }) { dir ->
                val isSel = dir in selected
                FolderRow(
                    store, dir, infos.getValue(dir), selected = isSel,
                    onClick = {
                        if (selecting) selected = if (isSel) selected - dir else selected + dir
                        else onOpenFolder(dir)
                    },
                    onLongClick = { selected = selected + dir },
                )
            }
            if (content != null && !isRoot && subfolders.isEmpty() && photos.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.empty_folder),
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
                                Icons.Default.GraphicEq, stringResource(R.string.voice_note),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                formatWhen(context, photo.lastModified),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    } else {
                        val thumb by rememberThumb(store, photo)
                        AsyncImage(
                            model = thumb,
                            contentDescription = photo.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().padding(if (isSel) 3.dp else 0.dp),
                        )
                    }
                    if (photo.isVideo) {
                        Icon(
                            Icons.Default.PlayCircle, stringResource(R.string.video), tint = Color.White,
                            modifier = Modifier.align(Alignment.Center).size(36.dp)
                        )
                    }
                    if (photo.isVideo || photo.isAudio) {
                        val duration by rememberDuration(store, photo)
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
                    if (photo in noted) {
                        Icon(
                            Icons.Default.EditNote, stringResource(R.string.has_note),
                            tint = Navy,
                            modifier = Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)
                                .background(SafetyYellow, MaterialTheme.shapes.small).padding(2.dp),
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

    if (recordingAudio) AudioRecordDialog(store, folder) { recordingAudio = false }
    exportProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {},
            title = { Text(stringResource(R.string.exporting)) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { if (total > 0) done / total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (total > 0) {
                        Text(
                            stringResource(R.string.position, done, total),
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
        )
    }
    noteFor?.let { NoteDialog(store, it) { noteFor = null } }

    when (dialog) {
        FolderDialog.Create -> NameDialog(
            title = if (isRoot) stringResource(R.string.new_project) else stringResource(R.string.new_subfolder_in, folder.name),
            initial = "",
            onDismiss = { dialog = null },
            onConfirm = { name ->
                work({ store.createFolder(folder, name) }) { r ->
                    r.onSuccess { dialog = null; onOpenFolder(it) }
                        .onFailure { toast(it.message ?: context.getString(R.string.error)) }
                }
            },
        )
        FolderDialog.Rename -> NameDialog(
            title = stringResource(R.string.rename),
            initial = folder.name,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                work({ store.renameFolder(folder, name) }) { r ->
                    r.onSuccess { dialog = null; onOpenFolder(it) }
                        .onFailure { toast(it.message ?: context.getString(R.string.error)) }
                }
            },
        )
        FolderDialog.DeleteFolder -> {
            val n = folderInfo?.total ?: 0
            ConfirmDialog(
                title = stringResource(R.string.delete_folder_q, folder.name),
                text = pluralStringResource(R.plurals.delete_folder_body, n, n),
                onDismiss = { dialog = null },
                onConfirm = {
                    dialog = null; onUp()
                    work({ store.deleteFolder(folder) }) { toast(context.getString(R.string.moved_to_trash)) }
                },
            )
        }
        FolderDialog.DeletePhotos -> {
            val hasFolders = selected.any { it.isDirectory }
            val n by produceState(0, selected) { value = withContext(Dispatchers.IO) { store.mediaIn(selected).size } }
            ConfirmDialog(
                title = if (hasFolders) stringResource(R.string.delete_selected_q)
                else pluralStringResource(R.plurals.delete_n_files_q, selected.size, selected.size),
                text = if (hasFolders) pluralStringResource(R.plurals.delete_selected_body, n, n)
                else stringResource(R.string.files_deleted_permanently),
                onDismiss = { dialog = null },
                onConfirm = {
                    val items = selected
                    selected = emptySet(); dialog = null
                    work({ store.deleteItems(items) }) { toast(context.getString(R.string.moved_to_trash)) }
                },
            )
        }
        FolderDialog.Move -> MoveDialog(
            store = store,
            exclude = folder,
            blocked = selected.filter { it.isDirectory }.toSet(),
            allowRoot = selected.all { it.isDirectory },
            onDismiss = { dialog = null },
            onPick = { dest ->
                val items = selected
                selected = emptySet(); dialog = null
                val name = if (dest.isRoot) context.getString(R.string.projects) else dest.name
                work({ store.moveItems(items, dest) }) { toast(context.getString(R.string.moved_to, name)) }
            },
        )
        null -> {}
    }
}

/**
 * "Projects › 111070 NorMec - Befaring › Elektro": every step is tappable. Scrolls
 * sideways when the path is long, and always starts scrolled to the current folder.
 * Sits at the top of the content, so it scrolls away with the photos.
 */
@Composable
private fun Breadcrumb(folder: Doc, onNavigate: (Doc) -> Unit) {
    val parts = folder.path.split('/')
    val steps = listOf(Doc("", true) to stringResource(R.string.projects)) +
        parts.indices.map { i -> Doc(parts.take(i + 1).joinToString("/"), true) to parts[i] }
    val scroll = rememberScrollState()
    LaunchedEffect(folder) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { i, (doc, label) ->
            val current = i == steps.lastIndex
            if (i > 0) {
                Icon(
                    Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(enabled = !current) { onNavigate(doc) }
                    .widthIn(max = 220.dp)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderHeader(info: PhotoStore.FolderInfo, note: String?, onEditNote: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val context = LocalContext.current
        Text(formatCounts(context, info), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.created_on, formatDate(context, info.created)) +
                (info.lastAdded?.let { " · " + stringResource(R.string.last_added, formatWhen(context, it)) } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (note != null) {
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .clickable(onClick = onEditNote).padding(12.dp),
            ) {
                Icon(
                    Icons.Default.EditNote, null, tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(end = 10.dp).size(20.dp),
                )
                Text(note, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            TextButton(onClick = onEditNote, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.EditNote, null, modifier = Modifier.size(18.dp))
                Text("  " + stringResource(R.string.add_note))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    store: PhotoStore,
    dir: Doc,
    info: PhotoStore.FolderInfo,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else ListItemDefaults.colors(),
        leadingContent = {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (info.cover != null) {
                    val thumb by rememberThumb(store, info.cover)
                    AsyncImage(
                        model = thumb, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                }
                if (selected) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primaryContainer)
                    }
                }
            }
        },
        headlineContent = { Text(dir.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                val context = LocalContext.current
                Text(formatCounts(context, info), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    info.lastAdded?.let { stringResource(R.string.last_added, formatWhen(context, it)) }
                        ?: stringResource(R.string.created_on, formatDate(context, info.created)),
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
                singleLine = true, label = { Text(stringResource(R.string.name)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
