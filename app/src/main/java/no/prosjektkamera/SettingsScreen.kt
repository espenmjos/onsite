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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
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
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            // Per-app language needs Android 13+; older phones just follow the system.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Section(stringResource(R.string.section_language))
                LanguageChoice()
            }

            Section(stringResource(R.string.projects))
            Choices(
                stringResource(R.string.sorting), stringResource(R.string.sorting_desc),
                listOf(
                    stringResource(R.string.sort_name) to !s.sortByRecent,
                    stringResource(R.string.sort_recent) to s.sortByRecent,
                ),
            ) { s.sortByRecent = it == 1 }

            Section(stringResource(R.string.camera))
            Choices(stringResource(R.string.aspect_ratio), null, listOf("4:3" to !s.wide, "16:9" to s.wide)) {
                s.wide = it == 1
            }
            Choices(
                stringResource(R.string.video_quality), stringResource(R.string.video_quality_desc),
                AppSettings.videoQualities.map { (name, _) -> name to (s.videoQuality == name) },
            ) { s.videoQuality = AppSettings.videoQualities[it].first }
            Toggle(stringResource(R.string.grid), stringResource(R.string.grid_desc), s.grid) { s.grid = it }
            Toggle(
                stringResource(R.string.volume_shutter), stringResource(R.string.volume_shutter_desc),
                s.volumeShutter,
            ) { s.volumeShutter = it }

            Section(stringResource(R.string.section_stamp))
            Toggle(stringResource(R.string.stamp_enabled), stringResource(R.string.stamp_desc), s.stampEnabled) {
                s.stampEnabled = it
            }
            Toggle(
                stringResource(R.string.stamp_location), stringResource(R.string.stamp_location_desc),
                s.stampLocation,
            ) { s.stampLocation = it }

            Section(stringResource(R.string.section_audio))
            Toggle(stringResource(R.string.high_audio), stringResource(R.string.high_audio_desc), s.highAudioQuality) {
                s.highAudioQuality = it
            }

            Section(stringResource(R.string.section_storage))
            Toggle(
                stringResource(R.string.show_in_gallery), stringResource(R.string.show_in_gallery_desc),
                galleryVisible,
            ) { store.visibleInGallery = it }
            ListItem(
                headlineContent = { Text(stringResource(R.string.location)) },
                supportingContent = { Text(stringResource(R.string.location_value)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.used_space)) },
                supportingContent = {
                    Text(
                        summary?.let { (n, bytes) -> pluralStringResource(R.plurals.n_files_size, n, n, formatSize(bytes)) }
                            ?: stringResource(R.string.calculating)
                    )
                },
            )

            Section(stringResource(R.string.section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.version, appVersion ?: "")) },
            )
        }
    }
}

/**
 * System / Norsk / English. Uses Android's per-app language, so the choice also
 * shows up under the app in system settings, and the activity restarts in the new language.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageChoice() {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(LocaleManager::class.java) }
    val current = remember { manager.applicationLocales.toLanguageTags() }
    val options = listOf(
        "" to stringResource(R.string.lang_system),
        "nb" to "Norsk",
        "en" to "English",
    )
    Choices(
        stringResource(R.string.section_language), null,
        options.map { (tag, label) -> label to (current.substringBefore('-') == tag) },
    ) { i ->
        val tag = options[i].first
        manager.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
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
