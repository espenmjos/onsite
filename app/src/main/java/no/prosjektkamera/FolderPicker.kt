package no.prosjektkamera

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Browse-style folder picker: tap a folder to go into it, arrow to go up,
 * then "Flytt hit" to choose the folder currently shown. Starts next to
 * [exclude] (in its parent) since that's usually where the target is.
 */
@Composable
fun MoveDialog(
    store: PhotoStore,
    exclude: File,
    onDismiss: () -> Unit,
    /** Folders being moved: they can't be entered, since a folder can't go inside itself. */
    blocked: Set<File> = emptySet(),
    /** Folders may be moved to the top level (becoming projects); files may not. */
    allowRoot: Boolean = false,
    title: String = stringResource(R.string.move_to),
    confirmLabel: String = stringResource(R.string.move_here),
    /** Where browsing starts; defaults to next to [exclude]. */
    start: File? = null,
    onPick: (File) -> Unit,
) {
    val context = LocalContext.current
    var current by remember {
        mutableStateOf(
            start?.takeIf { it.isDirectory && it.startsWith(store.root) }
                ?: exclude.parentFile?.takeIf { it.startsWith(store.root) } ?: store.root
        )
    }
    var creating by remember { mutableStateOf(false) }
    val version = store.version.intValue
    val children = remember(current, version) { store.subfolders(current) }
    val isRoot = current == store.root
    val isSource = current == exclude

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Location bar: up arrow + breadcrumb
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !isRoot, onClick = { current = current.parentFile!! }) {
                        Icon(
                            if (isRoot) Icons.Default.Home else Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.up),
                        )
                    }
                    Text(
                        if (isRoot) stringResource(R.string.projects)
                        else stringResource(R.string.projects) + " › " + store.relativePath(current).replace("/", " › "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
                Box(Modifier.height(320.dp)) {
                    if (children.isEmpty()) {
                        Text(
                            stringResource(R.string.no_subfolders),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                    LazyColumn {
                        items(children, key = { it.path }) { dir ->
                            val hasChildren = remember(dir, version) { store.subfolders(dir).isNotEmpty() }
                            val count = remember(dir, version) { store.media(dir).size }
                            val isBlocked = dir in blocked
                            ListItem(
                                modifier = Modifier.clickable(enabled = !isBlocked) { current = dir }
                                    .alpha(if (isBlocked) 0.4f else 1f),
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = {
                                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                                },
                                headlineContent = {
                                    Text(
                                        if (dir == exclude) stringResource(R.string.folder_current, dir.name) else dir.name,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = { Text(pluralStringResource(R.plurals.n_files, count, count)) },
                                trailingContent = if (hasChildren) {
                                    { Icon(Icons.Default.ChevronRight, null) }
                                } else null,
                            )
                        }
                    }
                }
                HorizontalDivider()
                TextButton(onClick = { creating = true }) {
                    Icon(Icons.Default.CreateNewFolder, null)
                    Text("  " + stringResource(R.string.new_folder_here))
                }
            }
        },
        confirmButton = {
            Button(enabled = (!isRoot || allowRoot) && !isSource, onClick = { onPick(current) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (creating) {
        NameDialog(
            title = if (isRoot) stringResource(R.string.new_project) else stringResource(R.string.new_folder_in, current.name),
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                store.createFolder(current, name)
                    .onSuccess { creating = false; current = it }
                    .onFailure { Toast.makeText(context, it.message ?: context.getString(R.string.error), Toast.LENGTH_SHORT).show() }
            },
        )
    }
}
