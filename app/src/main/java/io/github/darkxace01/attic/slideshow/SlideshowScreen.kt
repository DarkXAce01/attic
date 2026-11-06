package io.github.darkxace01.attic.slideshow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.data.StoryPlayer
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The photos one after another, full screen, each drifting slowly in toward the faces in it. A photo with
 * a story stays up while the story plays, so the family can sit and listen. Tap for the controls; the
 * screen stays on. Cast the phone's screen to a TV to watch it together.
 */
@Composable
fun SlideshowScreen(albumId: Long?, personId: Long?, onDone: () -> Unit) {
    val library = LocalContext.current.library
    val source: Flow<List<PrintEntity>> = remember(albumId, personId) {
        when {
            personId != null -> library.printsOf(personId)
            albumId != null -> library.printsIn(albumId)
            else -> library.printsOf(-1)
        }
    }
    val prints by source.collectAsStateWithLifecycle(null)
    val list = prints ?: return
    if (list.isEmpty()) { LaunchedEffect(Unit) { onDone() }; return }

    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var withStories by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(false) }
    val player = remember { StoryPlayer() }
    val playing by player.playing.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; player.stop() }
    }
    BackHandler(onBack = onDone)

    // One slide: its story (if any, and stories are on) plays out; otherwise a few quiet seconds.
    LaunchedEffect(index, paused, withStories) {
        player.stop()
        if (paused) return@LaunchedEffect
        val p = list[index % list.size]
        val story = if (withStories) withContext(Dispatchers.IO) { library.storiesNow(p.id).firstOrNull() } else null
        if (story != null) {
            delay(900)
            player.play(p.id, library.storyFile(p, story))
            // Until it's over (or couldn't start), then a breath.
            while (player.playing.value == p.id) delay(200)
            delay(1500)
        } else {
            delay(SLIDE_MS.toLong())
        }
        index = (index + 1) % list.size
    }
    // Controls hide themselves.
    LaunchedEffect(controls) { if (controls) { delay(4000); controls = false } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
    ) {
        Crossfade(targetState = index % list.size, animationSpec = tween<Float>(1200), label = "slide") { i ->
            Slide(list[i], drift = if (playing == list[i].id) 20_000 else SLIDE_MS + 1200)
        }
        Caption(list[index % list.size], Modifier.align(Alignment.BottomCenter))
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuietButton("Close", color = Color.White, onClick = onDone)
                Text("${index % list.size + 1} of ${list.size}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                QuietButton("Back", color = Color.White, onClick = { index = (index - 1 + list.size) % list.size; controls = true })
                QuietButton(if (paused) "Play" else "Pause", color = Color.White, onClick = { paused = !paused; controls = true })
                QuietButton("Next", color = Color.White, onClick = { index = (index + 1) % list.size; controls = true })
                QuietButton(if (withStories) "Stories on" else "Stories off", color = if (withStories) Color.White else Color.White.copy(alpha = 0.7f), onClick = { withStories = !withStories; controls = true })
            }
        }
    }
}

/** One photo, filling the screen without cropping, easing in toward its faces (or a quiet drift). */
@Composable
private fun Slide(p: PrintEntity, drift: Int) {
    val library = LocalContext.current.library
    val zoom = remember(p.id) { Animatable(1f) }
    var focus by remember(p.id) { mutableStateOf(0.5f to 0.5f) }
    LaunchedEffect(p.id) {
        // Toward the faces when there are any: the middle of the biggest one.
        library.facesOf(p.id).first().filter { !it.ignored }.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }?.let { f ->
            focus = (f.left + f.right) / 2 to (f.top + f.bottom) / 2
        } ?: run { focus = if (p.id % 2 == 0L) 0.42f to 0.45f else 0.58f to 0.55f }
        zoom.animateTo(1.12f, tween(drift, easing = LinearEasing))
    }
    AsyncImage(
        model = library.displayFile(p),
        contentDescription = p.caption,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .graphicsLayer {
                scaleX = zoom.value; scaleY = zoom.value
                transformOrigin = TransformOrigin(focus.first, focus.second)
            },
    )
}

/** When, where, who: one quiet line at the bottom. */
@Composable
private fun Caption(p: PrintEntity, modifier: Modifier) {
    val library = LocalContext.current.library
    val people by remember(p.id) { library.peopleIn(p.id) }.collectAsStateWithLifecycle(emptyList())
    val line = listOfNotNull(p.caption, people.joinToString(", ").ifEmpty { null }, p.date?.label, p.place).joinToString(" · ")
    if (line.isEmpty()) return
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp)) {
        Text(
            line,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** How long a photo without a story stays up. */
private const val SLIDE_MS = 6000
