package io.github.darkxace01.attic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.ui.signature.GreasePencilLoop
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.ui.theme.themeShadow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The tour: a few stops round the real app, each thing circled in grease pencil with a line or two
 * about what it does. Offered once on first open, skippable at any stop, and there again in Settings.
 */
object Tour {
    /** Where each thing the tour can point at is on screen right now, by name. */
    val targets = mutableStateMapOf<String, Rect>()

    /** A stop: which tab it's on, what it circles, and what it says. */
    class Stop(val tab: String, val target: String, val title: String, val body: String, val optional: Boolean = false)

    val stops = listOf(
        Stop("Albums", "tab.Albums", "Your albums", "Each album is a little island with its photo in the scene. Tap one to open it, or hold it to choose its scene and how its title is written."),
        Stop("Albums", "albums.layouts", "Path, ring or map", "Lay the islands out down a path, round a ring, or scattered across a map.", optional = true),
        Stop("Albums", "albums.new", "A new album", "Make one for each real album you have. Photos already on your phone can go in one too, without being copied."),
        Stop("Albums", "albums.menu", "Settings", "Themes, backup, the keepsake folder and how new scans look are all in this menu."),
        Stop("People", "tab.People", "People", "Name a face once and Attic finds it in other photos, then helps you build the family tree."),
        Stop("Timeline", "tab.Timeline", "Every photo by year", "The timeline runs from the oldest photo. Search finds people, places, years and captions."),
        Stop("Scan", "tab.Scan", "Scanning", "Point the phone at a print or a whole album page. Attic finds each photo, straightens it and takes it when the phone is steady."),
    )
}

/** Marks this as something the tour can circle, under [name]. */
fun Modifier.tourTarget(name: String): Modifier = composed {
    DisposableEffect(name) { onDispose { Tour.targets.remove(name) } }
    onGloballyPositioned { Tour.targets[name] = it.boundsInRoot() }
}

/**
 * One stop of the tour over the whole app: the rest of the screen dimmed, the thing itself circled in
 * grease pencil as the stop opens, and a card with what it does. Nothing underneath can be tapped while
 * the tour is open; Skip ends it at once.
 */
@Composable
fun TourOverlay(index: Int, onNext: () -> Unit, onSkip: () -> Unit) {
    val stop = Tour.stops[index]
    val last = index == Tour.stops.lastIndex
    val target = Tour.targets[stop.target]
    val density = LocalDensity.current
    // A stop for something that isn't there yet (no albums to lay out) passes by itself.
    androidx.compose.runtime.LaunchedEffect(index) {
        kotlinx.coroutines.delay(700)
        if (stop.optional && Tour.targets[stop.target] == null) onNext()
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } },
    ) {
        val pad = with(density) { 12.dp.toPx() }
        val hole = target?.let { Rect(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
        Canvas(Modifier.fillMaxSize()) {
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                hole?.let { addRoundRect(RoundRect(it, CornerRadius(it.height / 2))) }
            }
            drawPath(scrim, Palette.Ink.copy(alpha = if (Palette.dark) 0.6f else 0.45f))
        }
        if (hole != null) {
            val w = with(density) { hole.width.toDp() }
            val h = with(density) { hole.height.toDp() }
            val stroke = with(density) { 3.5.dp.toPx() } / max(1f, minOf(hole.width, hole.height))
            GreasePencilLoop(
                Modifier.offset { IntOffset(hole.left.roundToInt(), hole.top.roundToInt()) }.size(w, h),
                color = Palette.Accent, strokeWidth = stroke, key = index,
            )
        }
        // The card sits away from what's circled: at the top when that's low on the screen.
        val low = target == null || target.center.y > with(density) { maxHeight.toPx() } * 0.5f
        Column(
            Modifier
                .align(if (low) Alignment.TopCenter else Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(horizontal = 20.dp, vertical = 28.dp)
                .fillMaxWidth()
                .drawBehind { themeShadow(Offset.Zero, size, 18.dp.toPx(), 6.dp.toPx(), Palette.Ground) }
                .background(Palette.Ground, RoundedCornerShape(18.dp))
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
        ) {
            Text(stop.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Ink)
            Spacer(Modifier.height(6.dp))
            Text(stop.body, style = MaterialTheme.typography.bodyLarge, color = Palette.Muted, modifier = Modifier.padding(end = 8.dp))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} of ${Tour.stops.size}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted, modifier = Modifier.weight(1f))
                if (!last) QuietButton("Skip tour", onClick = onSkip)
                QuietButton(if (last) "Start scanning" else "Next", onClick = onNext, color = Palette.Accent)
            }
        }
    }
}
