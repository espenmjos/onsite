package no.prosjektkamera

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.log10

/**
 * Records an AAC/M4A voice note straight into [folder]. The dialog can't be
 * dismissed by tapping outside, so a recording is only ever saved or discarded
 * explicitly.
 */
@Composable
fun AudioRecordDialog(store: PhotoStore, folder: File, onDone: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (!it) {
            Toast.makeText(context, "Lydopptak krever tilgang til mikrofonen", Toast.LENGTH_LONG).show()
            onDone()
        }
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    if (!granted) return

    val highQuality = remember { AppSettings.get(context).highAudioQuality }
    val file = remember { store.newAudioFile(folder) }
    var paused by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }

    val recorder = remember {
        runCatching {
            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder())
                .apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(if (highQuality) 48_000 else 44_100)
                    setAudioEncodingBitRate(if (highQuality) 192_000 else 96_000)
                    setOutputFile(file.path)
                    prepare()
                    start()
                }
        }.onFailure {
            file.delete()
            Toast.makeText(context, "Kunne ikke starte opptak: ${it.message}", Toast.LENGTH_LONG).show()
        }.getOrNull()
    }
    if (recorder == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    var finished by remember { mutableStateOf(false) }
    fun finish(save: Boolean) {
        if (finished) return
        finished = true
        val ok = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        if (save && ok) {
            store.mediaSaved(file)
            Toast.makeText(context, "Lydopptak lagret", Toast.LENGTH_SHORT).show()
        } else {
            file.delete()
            if (save) Toast.makeText(context, "Opptaket var for kort", Toast.LENGTH_SHORT).show()
        }
        onDone()
    }
    // If the dialog disappears some other way (e.g. app closed), keep what was recorded.
    DisposableEffect(Unit) { onDispose { finish(save = true) } }

    // Timer and input level, sampled ~10x per second.
    LaunchedEffect(paused) {
        var last = SystemClock.elapsedRealtime()
        while (!paused) {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            elapsed += now - last
            last = now
            val amp = runCatching { recorder.maxAmplitude }.getOrDefault(0)
            level = if (amp <= 0) 0f else ((20 * log10(amp / 32767.0) + 50) / 50).toFloat().coerceIn(0f, 1f)
        }
        level = 0f
    }

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        icon = {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(if (paused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
            }
        },
        title = { Text(if (paused) "Pauset" else "Tar opp lyd …") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatDuration(elapsed), fontSize = 40.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { level },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = {
                            if (paused) recorder.resume() else recorder.pause()
                            paused = !paused
                        },
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            if (paused) "Fortsett" else "Pause",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Lagres i ${folder.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { finish(save = true) }) { Text("Lagre") } },
        dismissButton = {
            TextButton(onClick = { finish(save = false) }) {
                Text("Forkast", color = MaterialTheme.colorScheme.error)
            }
        },
    )
}
