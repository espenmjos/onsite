package no.prosjektkamera

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: PhotoStore, onClose: () -> Unit) {
    val context = LocalContext.current
    val s = remember { AppSettings.get(context) }
    val version = store.version.intValue
    val galleryVisible = remember(version) { store.visibleInGallery }
    val summary by produceState<Pair<Int, Long>?>(null, version) {
        value = withContext(Dispatchers.IO) { store.storageSummary() }
    }
    val appVersion = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Innstillinger") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Tilbake") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section("Prosjekter")
            Choices(
                "Sortering", "Rekkefølge på prosjekter og mapper",
                listOf("Navn" to !s.sortByRecent, "Sist endret" to s.sortByRecent),
            ) { s.sortByRecent = it == 1 }

            Section("Kamera")
            Choices("Bildeformat", null, listOf("4:3" to !s.wide, "16:9" to s.wide)) { s.wide = it == 1 }
            Choices(
                "Videokvalitet", "Høyere kvalitet gir større filer",
                AppSettings.videoQualities.map { (name, _) -> name to (s.videoQuality == name) },
            ) { s.videoQuality = AppSettings.videoQualities[it].first }
            Toggle("Rutenett", "Hjelpelinjer i søkeren", s.grid) { s.grid = it }
            Toggle("Volumknapper som utløser", "Ta bilde / start video med volumknappene", s.volumeShutter) {
                s.volumeShutter = it
            }

            Section("Lydopptak")
            Toggle("Høy lydkvalitet", "192 kbit/s i stedet for 96 kbit/s", s.highAudioQuality) {
                s.highAudioQuality = it
            }

            Section("Lagring")
            Toggle(
                "Vis i Galleri",
                "Vis bilder og video fra appen i Galleri / Google Foto",
                galleryVisible,
            ) { store.visibleInGallery = it }
            ListItem(
                headlineContent = { Text("Plassering") },
                supportingContent = { Text("Interne lagring › Pictures › Prosjektkamera") },
            )
            ListItem(
                headlineContent = { Text("Brukt plass") },
                supportingContent = {
                    Text(summary?.let { (n, bytes) -> "$n filer · ${formatSize(bytes)}" } ?: "Beregner …")
                },
            )

            Section("Om")
            ListItem(
                headlineContent = { Text("Prosjektkamera") },
                supportingContent = { Text("Versjon $appVersion") },
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun Choices(title: String, subtitle: String?, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                subtitle?.let { Text(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    options.forEachIndexed { i, (label, selected) ->
                        FilterChip(selected = selected, onClick = { onPick(i) }, label = { Text(label) })
                    }
                }
            }
        },
    )
}
