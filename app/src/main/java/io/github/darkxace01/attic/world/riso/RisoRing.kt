package io.github.darkxace01.attic.world.riso

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.darkxace01.attic.ui.theme.Palette
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The albums standing on a ring, one in front, the rest going round the back smaller. Swipe sideways to
 * turn it; it settles with one album facing you, its title underneath. Tap the front one to open it (hold
 * to make it yours); tap one at the side to bring it round.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RisoRing(
    islands: List<WorldIsland>,
    time: () -> Float,
    onOpen: (Long) -> Unit,
    onDress: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = islands.size
    if (n == 0) return
    // Up to eight make a whole ring that goes round and round; more turn a wheel, eight in view at once.
    val wraps = n <= MAX_ON_RING
    val step = (2 * PI / if (wraps) n.coerceAtLeast(3) else MAX_ON_RING).toFloat()
    // Where the ring has turned to, in albums: 2.0 is the third album right in front.
    val pos = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    fun frontIndex(): Int = if (wraps) Math.floorMod(pos.value.roundToInt(), n) else pos.value.roundToInt().coerceIn(0, n - 1)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val cell = minOf(w * 0.8f, h * 0.58f)
        val cellDp = with(density) { cell.toDp() }
        // A full-width swipe turns it about three albums.
        val perAlbum = w / 3.2f

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(n) {
                    val tracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { tracker.resetTracking() },
                        onDragEnd = {
                            val v = tracker.calculateVelocity().x / perAlbum
                            var target = (pos.value - v * 0.22f).roundToInt().toFloat()
                            if (!wraps) target = target.coerceIn(0f, (n - 1).toFloat())
                            scope.launch { pos.animateTo(target, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow), initialVelocity = -v) }
                        },
                    ) { change, dx ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        var next = pos.value - dx / perAlbum
                        // Past either end of a wheel, it gives a little and springs back.
                        if (!wraps && (next < 0f || next > n - 1f)) next = pos.value - dx / perAlbum * 0.3f
                        scope.launch { pos.snapTo(next) }
                    }
                },
        ) {
            // The ring itself, printed as a dotted track the islands stand on.
            Canvas(Modifier.fillMaxSize().zIndex(-2f)) {
                val dot = 2.2.dp.toPx()
                val ground = cell * 0.24f
                for (k in 0 until 72) {
                    val t = k / 72f * 2 * PI.toFloat()
                    val zz = cos(t)
                    drawCircle(
                        Palette.Ink.copy(alpha = 0.18f + 0.3f * (zz + 1) / 2),
                        dot * (0.6f + 0.4f * (zz + 1) / 2),
                        Offset(w / 2 + sin(t) * w * RX, h * (CY + RY * zz) + ground * ringScale((zz + 1) / 2)),
                    )
                }
            }
            for ((i, isl) in islands.withIndex()) {
                var d = i - pos.value
                if (wraps) d = ((d % n) + n + n / 2f) % n - n / 2f
                val a = d * step
                if (!wraps && abs(a) > PI * 0.95) continue
                val z = cos(a) // 1 in front, -1 right at the back
                val near = (z + 1) / 2
                val scale = ringScale(near)
                val cx = w / 2 + sin(a) * w * RX
                // The far side of the ring sits higher up the page, as if seen from a little above.
                val cy = h * (CY + RY * z)
                Box(
                    Modifier
                        .zIndex(z)
                        .offset { IntOffset((cx - cell / 2).roundToInt(), (cy - cell / 2).roundToInt()) }
                        .size(cellDp)
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            // Further round, fainter, the way a light second pass of ink sits back.
                            alpha = 0.45f + 0.55f * near
                        }
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = {
                                if (abs(d) < 0.5f) onOpen(isl.id)
                                else scope.launch { pos.animateTo(pos.value + d, tween(520)) }
                            },
                            onLongClick = { if (abs(d) < 0.5f) onDress(isl.id) },
                        ),
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawIsland(isl.look, isl.photos, Offset(size.width / 2, size.height / 2), size.width * 0.33f, time(), i)
                    }
                }
            }

            // The front album's name, set the way its owner chose, and where it is in the ring.
            val front by remember { androidx.compose.runtime.derivedStateOf { frontIndex() } }
            AnimatedContent(
                targetState = front,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "front",
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp).zIndex(2f),
            ) { k ->
                val isl = islands.getOrNull(k) ?: return@AnimatedContent
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AlbumTitle(isl.title, isl.meta, isl.look.title, Modifier.widthIn(max = 300.dp), center = true)
                    Text(
                        "${k + 1} of $n",
                        style = MaterialTheme.typography.labelMedium, color = Palette.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/** The ring's half-width (of the screen's width), its centre and its depth (of the height). */
private const val RX = 0.44f
private const val CY = 0.39f
private const val RY = 0.13f

/** How big an album stands, from 0 at the very back of the ring to 1 in front. */
private fun ringScale(near: Float): Float { val n2 = near * near; return 0.22f + 0.78f * n2 * n2 }

/** How many albums stand round a whole ring before it becomes a wheel. */
private const val MAX_ON_RING = 8
