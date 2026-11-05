package io.github.darkxace01.attic.stories

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.data.Recorder
import io.github.darkxace01.attic.data.storyLength
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A story session, for sitting with someone who remembers: the album's photos come up one at a time, big,
 * while the phone listens. Whatever is said while a photo is on screen becomes that photo's story; swipe
 * (or Next) to move on. Photos nobody talked about get nothing.
 */
@Composable
fun SessionScreen(albumId: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val library = context.library
    val prints by remember(albumId) { library.printsIn(albumId) }.collectAsStateWithLifecycle(null)
    val list = prints ?: return
    if (list.isEmpty()) { onDone(); return }

    val recorder = remember { Recorder(context) }
    var started by remember { mutableStateOf(false) }
    var micRefused by remember { mutableStateOf(false) }
    var saved by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    // The loudest the voice got while this photo was up: under SPEECH, nobody spoke about it.
    var peak by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val pager = rememberPagerState { list.size }
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    /** Ends the recording for the photo at [index]: kept as its story if anyone spoke. */
    fun close(index: Int) {
        val clip = recorder.stop()
        val spoke = peak >= SPEECH
        peak = 0f
        val p = list.getOrNull(index)
        if (clip == null || p == null) return
        if (!spoke) { clip.first.delete(); return }
        saved++
        // Not tied to the screen: the story is kept even if the session is left right now.
        CoroutineScope(Dispatchers.IO).launch { library.addStory(p.id, clip.first, clip.second) }
    }

    fun begin() {
        started = runCatching { recorder.start() }.isSuccess
    }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micRefused = !granted
        if (granted) begin()
    }

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            if (recorder.recording) close(pager.currentPage)
        }
    }

    // A new photo, a new story.
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        var showing = pager.currentPage
        snapshotFlow { pager.settledPage }.collect { now ->
            if (now != showing && !finished) {
                close(showing)
                showing = now
                runCatching { recorder.start() }
            }
        }
    }
    LaunchedEffect(started) {
        if (!started) return@LaunchedEffect
        while (true) withFrameMillis {
            val l = recorder.level()
            peak = maxOf(peak, l)
            level += (l - level) * 0.25f
            elapsed = recorder.elapsed
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        HorizontalPager(pager, userScrollEnabled = started && !finished, modifier = Modifier.fillMaxSize()) { i ->
            AsyncImage(
                model = library.displayFile(list[i]),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 72.dp),
            )
        }

        when {
            finished -> Message(
                when (saved) {
                    0 -> "Nothing was said, so no stories were kept."
                    1 -> "Kept a story for 1 photo."
                    else -> "Kept stories for $saved photos."
                },
            )
            !started -> Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Palette.Ground)
                    .padding(20.dp),
            ) {
                Text("Tell the stories", style = MaterialTheme.typography.titleLarge, color = Palette.Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Sit with someone who remembers. The photos come up one at a time; talk about each one, then " +
                        "swipe to the next. Whatever is said while a photo is up becomes its story. Photos nobody " +
                        "talks about are left as they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Muted,
                )
                if (micRefused) {
                    Spacer(Modifier.height(6.dp))
                    Text("Attic needs the microphone for this. Recordings stay on this phone.", style = MaterialTheme.typography.bodyMedium, color = Palette.Warning)
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QuietButton("Not now", onClick = onDone)
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Start listening", onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
                        else askMic.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
            }
            else -> {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(22.dp)) {
                        drawCircle(Palette.Accent.copy(alpha = 0.25f), radius = size.minDimension / 2 * (0.45f + 0.55f * level))
                        drawCircle(Palette.Accent, radius = size.minDimension * 0.2f)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Listening ${storyLength(elapsed)}", style = MaterialTheme.typography.bodyLarge, color = Palette.Ink, modifier = Modifier.weight(1f))
                    Text("${pager.currentPage + 1} of ${list.size}", style = MaterialTheme.typography.bodyLarge, color = Palette.Muted)
                }
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuietButton("Finish", onClick = {
                        finished = true
                        close(pager.currentPage)
                        scope.launch { delay(1800); onDone() }
                    })
                    Spacer(Modifier.weight(1f))
                    if (pager.currentPage < list.lastIndex) {
                        PrimaryButton("Next photo", onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().background(Palette.Ground.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, modifier = Modifier.padding(32.dp))
    }
}

/** A voice gets at least this loud (share of full scale); quieter is a room with nobody talking. */
private const val SPEECH = 0.06f
