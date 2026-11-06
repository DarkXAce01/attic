package io.github.darkxace01.attic.world.riso

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The albums as an archipelago on a sea chart: scattered round the newest one, the sea marked with
 * little pencil waves. Drag anywhere, in any direction, and let go to drift. Tap an island to open it,
 * hold to make it yours.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RisoMap(
    islands: List<WorldIsland>,
    time: () -> Float,
    onOpen: (Long) -> Unit,
    onDress: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (islands.isEmpty()) return
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cell = w * 0.56f
        val cellDp = with(density) { cell.toDp() }
        // A sunflower spiral: every island gets its own water, newest in the middle.
        val spots = remember(islands.size, w) {
            List(islands.size) { i ->
                val r = cell * 0.95f * sqrt(i.toFloat())
                val a = i * GOLDEN
                // A little unevenness, the same every time for the same album.
                val jx = ((islands[i].id * 37 % 11) - 5) * cell * 0.02f
                val jy = ((islands[i].id * 53 % 11) - 5) * cell * 0.02f
                Offset(r * cos(a) + jx, r * sin(a) * 0.9f + jy)
            }
        }
        val minX = spots.minOf { it.x }; val maxX = spots.maxOf { it.x }
        val minY = spots.minOf { it.y }; val maxY = spots.maxOf { it.y }
        // How far the chart moves: never so far that every island is off the page.
        fun clamp(o: Offset) = Offset(o.x.coerceIn(-maxX - cell * 0.2f, -minX + cell * 0.2f), o.y.coerceIn(-maxY - cell * 0.2f, -minY + cell * 0.2f))

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(islands.size) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { tracker.resetTracking(); scope.launch { pan.stop() } },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            scope.launch {
                                pan.animateDecay(Offset(v.x, v.y), exponentialDecay(frictionMultiplier = 1.4f)) {
                                    val c = clamp(value)
                                    if (c != value) launch { pan.snapTo(c) }
                                }
                            }
                        },
                    ) { change, drag ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch { pan.snapTo(clamp(pan.value + drag)) }
                    }
                },
        ) {
            // The sea: small pencil waves on a grid that moves with the chart.
            Canvas(Modifier.fillMaxSize()) {
                val gap = 64.dp.toPx()
                val o = pan.value
                val x0 = floor(-o.x / gap).toInt() - 1
                val y0 = floor(-o.y / gap).toInt() - 1
                val t = time()
                for (gx in x0..x0 + (size.width / gap).toInt() + 2) for (gy in y0..y0 + (size.height / gap).toInt() + 2) {
                    val hsh = (gx * 73856093) xor (gy * 19349663)
                    if (Math.floorMod(hsh, 5) != 0) continue
                    val bob = sin(t * 0.9f + gx * 0.7f + gy) * 2.dp.toPx()
                    val cx = gx * gap + o.x + w / 2 + Math.floorMod(hsh, 17) - 8
                    val cy = gy * gap + o.y + h / 2 + bob
                    wave(Offset(cx, cy), 9.dp.toPx())
                }
            }
            for ((i, isl) in islands.withIndex()) {
                val s = spots[i]
                Column(
                    Modifier
                        .offset {
                            IntOffset((w / 2 + s.x + pan.value.x - cell / 2).roundToInt(), (h / 2 + s.y + pan.value.y - cell / 2).roundToInt())
                        }
                        .width(cellDp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = { onOpen(isl.id) }, onLongClick = { onDress(isl.id) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.size(cellDp, cellDp * 0.82f)) {
                        drawIsland(isl.look, isl.photos, Offset(size.width / 2, size.height * 0.52f), size.width * 0.27f, time(), i)
                    }
                    AlbumTitle(isl.title, isl.meta, isl.look.title, Modifier.fillMaxWidth().graphicsLayer { scaleX = 0.8f; scaleY = 0.8f }, center = true)
                }
            }
        }
    }
}

/** A little wave, the way a map maker draws the sea: two shallow humps in a pale ink. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.wave(c: Offset, size: Float) {
    val p = androidx.compose.ui.graphics.Path().apply {
        moveTo(c.x - size, c.y)
        quadraticTo(c.x - size / 2, c.y - size * 0.5f, c.x, c.y)
        quadraticTo(c.x + size / 2, c.y - size * 0.5f, c.x + size, c.y)
    }
    drawPath(p, Ink.Aqua.color.copy(alpha = 0.7f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
}

private const val GOLDEN = 2.39996323f
