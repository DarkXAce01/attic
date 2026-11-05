package io.github.darkxace01.attic.world

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.ui.theme.Palette
import kotlin.math.PI
import kotlin.math.sin

/** An island with what's written beside it. */
class WorldAlbum(val island: Island, val title: String, val meta: String)

/**
 * The attic: albums float as islands in a dark, still space, one soft beam of light falling through it
 * from the window, dust turning in the beam. Islands sit at different depths, left and right, and the
 * nearer ones move a little faster as you scroll.
 *
 * [time] is in seconds and drives every motion, so the scene can be frozen for screenshots.
 */
@Composable
fun AlbumWorld(
    albums: List<WorldAlbum>,
    time: () -> Float,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
    state: LazyListState = rememberLazyListState(),
) {
    // The album being opened: where its island was when tapped, and how far open it is.
    var opening by remember { mutableStateOf<Opening?>(null) }
    val progress = remember { Animatable(0f) }
    var root by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(opening) {
        val o = opening ?: return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(OPEN_MS, easing = LinearEasing))
        onOpen(o.album.island.key)
        // If the screen is still here (nothing navigated), put the album back on its shelf.
        delay(400)
        opening = null
    }
    Box(modifier.fillMaxSize().onGloballyPositioned { root = it }) {
        Canvas(Modifier.fillMaxSize()) { drawAttic(time(), state.firstVisibleItemIndex * 900f + state.firstVisibleItemScrollOffset) }
        LazyColumn(state = state, userScrollEnabled = opening == null, contentPadding = PaddingValues(bottom = 80.dp), modifier = Modifier.fillMaxSize()) {
            item { header() }
            itemsIndexed(albums, key = { _, a -> a.island.key }) { i, a ->
                IslandRow(a, i, time, root = { root }, hidden = opening?.album?.island?.key == a.island.key) { centre, width ->
                    if (opening == null) opening = Opening(a, centre, width)
                }
            }
        }
        opening?.let { o ->
            Canvas(Modifier.fillMaxSize()) {
                val e = progress.value
                // Everything else dims as the album comes up to you.
                drawRect(ATTIC.copy(alpha = 0.85f * smooth(e)))
                val k = smooth(e)
                val endWidth = size.width * 1.04f
                val width = o.width + (endWidth - o.width) * k
                // Open, the cover lies to the left of the spine; the page you read is to the right, centred.
                val end = Offset(size.width / 2 + 0.5f * 0.46f * endWidth * k, size.height * 0.46f)
                val centre = Offset(o.centre.x + (end.x - o.centre.x) * k, o.centre.y + (end.y - o.centre.y) * k)
                drawIsland(o.album.island, centre, width, time(), open = (e * 1.15f - 0.1f).coerceIn(0f, 1f))
            }
        }
    }
}

private class Opening(val album: WorldAlbum, val centre: Offset, val width: Float)

private fun smooth(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

/** Where an island floats in its row, as a share of the row's height. */
private const val ISLAND_Y = 0.34f

/** How long an album takes to come up and fall open. */
private const val OPEN_MS = 1100

@Composable
private fun IslandRow(
    a: WorldAlbum,
    index: Int,
    time: () -> Float,
    root: () -> LayoutCoordinates?,
    hidden: Boolean,
    onOpen: (centre: Offset, width: Float) -> Unit,
) {
    // Left, right, a little in from the edge: a loose scatter rather than a column.
    val side = if (index % 2 == 0) -1f else 1f
    val depth = listOf(1f, 0.86f, 0.94f, 0.8f, 1.02f)[index % 5]
    var canvas by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val c = canvas ?: return@clickable
                val r = root() ?: return@clickable
                val w = c.size.width.toFloat()
                val at = r.localPositionOf(c, Offset(w / 2, c.size.height * ISLAND_Y))
                onOpen(at, w * 0.6f * depth)
            },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height((268 * depth).dp)
                .graphicsLayer { translationX = side * size.width * 0.13f }
                .onGloballyPositioned { canvas = it },
        ) {
            if (hidden) return@Canvas
            val w = size.width * 0.6f * depth
            drawIsland(a.island, Offset(size.width / 2, size.height * ISLAND_Y), w, time())
        }
        // The name under its island, on the island's side.
        Column(
            Modifier
                .fillMaxWidth()
                .offset(y = (-8).dp)
                .padding(start = if (side < 0) 40.dp else 0.dp, end = if (side > 0) 40.dp else 0.dp)
                .graphicsLayer { translationX = side * size.width * 0.13f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, color = Palette.Ink, textAlign = TextAlign.Center)
            Text(a.meta, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The attic itself: near-black, one beam of light slanting down from the top left (laid in a few soft
 * steps, the way light spreads), dust drifting through it, brighter where it crosses the beam.
 */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAttic(t: Float, scroll: Float) {
    drawRect(ATTIC)
    val w = size.width
    val h = size.height
    // The beam: wide at the bottom, from a window above the top-left corner.
    val top0 = Offset(-w * 0.15f, -h * 0.05f)
    val top1 = Offset(w * 0.35f, -h * 0.05f)
    for (k in 0 until 6) {
        val spread = k * w * 0.07f
        val beam = Path().apply {
            moveTo(top0.x - spread * 0.3f, top0.y)
            lineTo(top1.x + spread * 0.3f, top1.y)
            lineTo(w * 1.05f + spread, h * 1.02f)
            lineTo(w * 0.25f - spread, h * 1.02f)
            close()
        }
        drawPath(beam, Color(0xFFFFF4E0).copy(alpha = 0.012f))
    }
    // Dust: slow, each mote on its own drift, a little parallax with the scroll.
    for (i in 0 until 70) {
        val r1 = frac(i * 0.754877f)
        val r2 = frac(i * 0.569840f + 0.31f)
        val r3 = frac(i * 0.381966f + 0.77f)
        val x = frac(r1 + t * 0.004f * (0.5f + r3) + 0.02f * sin(t * 0.3f + i).toFloat()) * w
        val y = frac(r2 - t * 0.003f * (0.3f + r3) - scroll * 0.00002f * (0.5f + r3)) * h
        // How far inside the beam: the beam's centre line runs from (0.1w, 0) to (0.65w, h).
        val centre = w * (0.1f + 0.55f * (y / h))
        val inBeam = (1f - kotlin.math.abs(x - centre) / (w * 0.42f)).coerceIn(0f, 1f)
        val twinkle = 0.5f + 0.5f * sin(t * (0.8f + r3) + i * 2.1f).toFloat()
        val a = 0.05f + 0.4f * inBeam * twinkle
        drawCircle(Color(0xFFFFF1DC).copy(alpha = a), radius = 0.8f + 1.6f * r3, center = Offset(x, y))
    }
}

private fun frac(v: Float) = v - kotlin.math.floor(v)

/** The attic in the dark: a true neutral, a shade off black. */
val ATTIC = Color(0xFF0E0E0F)

/** The binding colours albums come in, for albums without photos to take a colour from. */
val CLOTHS = listOf(Color(0xFF6E2A26), Color(0xFF29473A), Color(0xFF263A5A), Color(0xFF7A5A2A), Color(0xFF4A2E4F), Color(0xFF3A3A36))

/** One full turn of [period] seconds, as 0..1. */
fun phase(t: Float, period: Float) = frac(t / period)

@Suppress("unused")
private const val TAU = (2 * PI).toFloat()
