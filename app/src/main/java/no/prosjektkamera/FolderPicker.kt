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
fun MoveDialog(store: PhotoStore, exclude: File, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf(exclude.parentFile?.takeIf { it.startsWith(store.root) } ?: store.root) }
    var creating by remember { mutableStateOf(false) }
    val version = store.version.intValue
    val children = remember(current, version) { store.subfolders(current) }
    val isRoot = current == store.root
    val isSource = current == exclude

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Flytt til") },
        text = {
            Column {
                // Location bar: up arrow + breadcrumb
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !isRoot, onClick = { current = current.parentFile!! }) {
                        Icon(
                            if (isRoot) Icons.Default.Home else Icons.AutoMirrored.Filled.ArrowBack,
                            "Opp",
                        )
                    }
                    Text(
                        if (isRoot) "Prosjekter"
                        else "Prosjekter › " + store.relativePath(current).replace("/", " › "),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
                Box(Modifier.height(320.dp)) {
                    if (children.isEmpty()) {
                        Text(
                            "Ingen undermapper her",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                        )
                    }
                    LazyColumn {
                        items(children, key = { it.path }) { dir ->
                            val hasChildren = remember(dir, version) { store.subfolders(dir).isNotEmpty() }
                            val count = remember(dir, version) { store.media(dir).size }
                            ListItem(
                                modifier = Modifier.clickable { current = dir },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = {
                                    Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                                },
                                headlineContent = {
                                    Text(
                                        dir.name + if (dir == exclude) " (nåværende)" else "",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                supportingContent = { Text(if (count == 1) "1 fil" else "$count filer") },
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
                    Text("  Ny mappe her")
                }
            }
        },
        confirmButton = {
            Button(enabled = !isRoot && !isSource, onClick = { onPick(current) }) { Text("Flytt hit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )

    if (creating) {
        NameDialog(
            title = if (isRoot) "Nytt prosjekt" else "Ny mappe i «${current.name}»",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                store.createFolder(current, name)
                    .onSuccess { creating = false; current = it }
                    .onFailure { Toast.makeText(context, it.message ?: "Feil", Toast.LENGTH_SHORT).show() }
            },
        )
    }
}
