package no.prosjektkamera

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.ceil
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(store: PhotoStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val version = store.version.intValue
    val entries = remember(version) { store.trashEntries() }
    var selected by remember { mutableStateOf(setOf<PhotoStore.TrashEntry>()) }
    // Drop selections that no longer exist after a restore/delete.
    val sel = selected.filter { it in entries }.toSet()
    var confirm by remember { mutableStateOf<Set<PhotoStore.TrashEntry>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (sel.isNotEmpty()) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, stringResource(R.string.cancel_selection))
                        }
                    } else {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                title = {
                    Text(
                        if (sel.isEmpty()) stringResource(R.string.trash)
                        else pluralStringResource(R.plurals.n_selected, sel.size, sel.size)
                    )
                },
                actions = {
                    if (sel.isNotEmpty()) {
                        IconButton(onClick = {
                            store.restore(sel)
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(R.plurals.restored_n, sel.size, sel.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                            selected = emptySet()
                        }) {
                            Icon(Icons.Default.RestoreFromTrash, stringResource(R.string.restore))
                        }
                        IconButton(onClick = { confirm = sel }) {
                            Icon(Icons.Default.DeleteForever, stringResource(R.string.delete_forever))
                        }
                    } else if (entries.isNotEmpty()) {
                        TextButton(onClick = { confirm = entries.toSet() }) {
                            Text(stringResource(R.string.empty_trash))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.trash_empty) + "\n\n" + stringResource(R.string.trash_info),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 24.dp)) {
            item {
                Text(
                    stringResource(R.string.trash_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(entries, key = { it.dir.path }) { e ->
                val isSel = e in sel
                val toggle = { selected = if (isSel) sel - e else sel + e }
                val days = max(1, ceil((e.expiresAt - System.currentTimeMillis()) / 86_400_000.0).toInt())
                val from = e.originalPath.substringBeforeLast('/', "").replace("/", " › ")
                    .ifEmpty { stringResource(R.string.projects) }
                ListItem(
                    modifier = Modifier.clickable(onClick = toggle),
                    leadingContent = {
                        Box(
                            Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                e.item.isDirectory -> Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                                e.item.isAudio -> Icon(Icons.Default.GraphicEq, null)
                                else -> AsyncImage(
                                    model = e.item, contentDescription = null,
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    },
                    headlineContent = { Text(e.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Column {
                            Text(stringResource(R.string.from_location, from), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                pluralStringResource(R.plurals.expires_in, days, days),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingContent = { Checkbox(checked = isSel, onCheckedChange = { toggle() }) },
                )
            }
        }
    }

    confirm?.let { targets ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    if (targets.size == entries.size) stringResource(R.string.empty_trash_q)
                    else pluralStringResource(R.plurals.delete_forever_q, targets.size, targets.size)
                )
            },
            text = { Text(stringResource(R.string.cannot_undo)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteForever(targets)
                    selected = emptySet()
                    confirm = null
                }) { Text(stringResource(R.string.delete_forever), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
