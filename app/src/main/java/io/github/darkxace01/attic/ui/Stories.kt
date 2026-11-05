package io.github.darkxace01.attic.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.data.Recorder
import io.github.darkxace01.attic.data.StoryEntity
import io.github.darkxace01.attic.data.StoryPlayer
import io.github.darkxace01.attic.data.storyLength
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The stories told about a photo: play them, and record a new one. Long-press a story to delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StoriesSection(p: PrintEntity) {
    val context = LocalContext.current
    val library = context.library
    val scope = rememberCoroutineScope()
    val stories by remember(p.id) { library.stories(p.id) }.collectAsStateWithLifecycle(emptyList())
    val player = remember { StoryPlayer() }
    val playing by player.playing.collectAsStateWithLifecycle()
    val recorder = remember { Recorder(context) }
    var recording by remember { mutableStateOf(false) }
    var micRefused by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<StoryEntity?>(null) }
    DisposableEffect(p.id) {
        onDispose {
            player.stop()
            recorder.cancel()
        }
    }

    fun start() {
        player.stop()
        recording = runCatching { recorder.start() }.isSuccess
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRefused = !granted
        if (granted) start()
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (stories.isNotEmpty()) {
            Text("Stories", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
            Spacer(Modifier.height(4.dp))
        }
        stories.forEachIndexed { i, s ->
            val on = playing == s.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (on) player.stop() else player.play(s.id, library.storyFile(p, s)) },
                        onLongClick = { deleting = s },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayMark(on)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (stories.size > 1) "Story ${i + 1}, " else "A story, ") + storyLength(s.durationMs),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Ink,
                    )
                    Text(
                        "Told on " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.createdAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.Muted,
                    )
                    if (on) PlayProgress(player)
                }
            }
        }

        if (recording) {
            RecordingRow(
                recorder,
                onKeep = {
                    recording = false
                    val (file, ms) = recorder.stop() ?: return@RecordingRow
                    scope.launch { library.addStory(p.id, file, ms) }
                },
                onCancel = {
                    recording = false
                    recorder.cancel()
                },
            )
        } else {
            QuietButton(
                if (stories.isEmpty()) "Record a story about this photo" else "Record another story",
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else askMic.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
            if (micRefused) {
                Text(
                    "Attic needs the microphone to record stories. They stay on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Warning,
                )
            }
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this story?") },
            text = { Text("The recording is gone for good, unless it's in a backup.") },
            confirmButton = {
                QuietButton("Delete", color = Palette.Warning, onClick = {
                    deleting = null
                    if (playing == s.id) player.stop()
                    scope.launch { library.deleteStory(s) }
                })
            },
            dismissButton = { QuietButton("Keep", onClick = { deleting = null }) },
            containerColor = Palette.Surface,
        )
    }
}

/** A play triangle, or a stop square while playing. */
@Composable
private fun PlayMark(on: Boolean) {
    Canvas(Modifier.size(28.dp)) {
        drawCircle(Palette.SurfaceHigh)
        val c = center
        val r = size.minDimension * 0.22f
        if (on) {
            drawRect(Palette.Ink, topLeft = Offset(c.x - r * 0.8f, c.y - r * 0.8f), size = androidx.compose.ui.geometry.Size(r * 1.6f, r * 1.6f))
        } else {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(c.x - r * 0.7f, c.y - r)
                lineTo(c.x + r * 1.1f, c.y)
                lineTo(c.x - r * 0.7f, c.y + r)
                close()
            }
            drawPath(path, Palette.Ink)
        }
    }
}

@Composable
private fun PlayProgress(player: StoryPlayer) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) withFrameMillis { t = player.progress }
    }
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val y = size.height / 2
        drawLine(Palette.SurfaceHigh, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
        drawLine(Palette.Ink, Offset(0f, y), Offset(size.width * t, y), 3.dp.toPx(), StrokeCap.Round)
    }
}

/** While recording: a dot that swells with the voice, the time so far, and keep or cancel. */
@Composable
private fun RecordingRow(recorder: Recorder, onKeep: () -> Unit, onCancel: () -> Unit) {
    var level by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recorder) {
        while (true) withFrameMillis {
            // Eased, so the dot breathes with the voice instead of flickering.
            level += (recorder.level() - level) * 0.25f
            elapsed = recorder.elapsed
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(Palette.Accent.copy(alpha = 0.25f), radius = size.minDimension / 2 * (0.5f + 0.5f * level))
                drawCircle(Palette.Accent, radius = size.minDimension * 0.18f)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("Recording ${storyLength(elapsed)}", style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
        QuietButton("Cancel", onClick = onCancel)
        PrimaryButton("Keep", onClick = onKeep)
    }
}
