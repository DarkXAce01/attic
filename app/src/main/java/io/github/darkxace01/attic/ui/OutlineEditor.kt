package io.github.darkxace01.attic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.vision.OutlineEdit
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import kotlin.math.hypot
import kotlin.math.min

/** Where [image] sits inside a box of [w] x [h]: fitted and centred. */
private class Fit(image: ImageBitmap, w: Float, h: Float) {
    val scale = min(w / image.width, h / image.height)
    val ox = (w - image.width * scale) / 2
    val oy = (h - image.height * scale) / 2
    fun toScreen(p: Pt) = Offset(ox + p.x * scale, oy + p.y * scale)
    fun toImage(o: Offset) = Pt((o.x - ox) / scale, (o.y - oy) / scale)
}

/** What a finger holds: corner 0..3, or side 4..7 (side k runs from corner k to corner k + 1). */
private data class Grab(val quad: Int, val handle: Int)

/**
 * The outlines of the prints on a photo, to put right by hand, the way a document scanner does: drag a
 * corner, or drag a side to move it straight in or out. Tap an outline to pick it. While dragging, a loupe
 * shows the spot magnified, since the finger covers exactly the spot being placed.
 *
 * [quads] are in [image] pixels. [selected] is the picked outline, -1 for none.
 */
@Composable
fun OutlineEditor(
    image: ImageBitmap,
    quads: List<Quad>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Int, Quad) -> Unit,
    modifier: Modifier = Modifier,
) {
    var grab by remember { mutableStateOf<Grab?>(null) }
    var live by remember { mutableStateOf<Quad?>(null) }
    val currentQuads by rememberUpdatedState(quads)
    val currentSelected by rememberUpdatedState(selected)
    val shown = { i: Int -> if (grab?.quad == i) live ?: quads[i] else quads[i] }

    Canvas(
        modifier
            .pointerInput(image) {
                detectTapGestures { at ->
                    val fit = Fit(image, size.width.toFloat(), size.height.toFloat())
                    val p = fit.toImage(at)
                    // The smallest outline under the finger, so one inside another can still be picked.
                    val hit = currentQuads.indices.filter { currentQuads[it].contains(p) }.minByOrNull { currentQuads[it].area } ?: -1
                    onSelect(hit)
                }
            }
            .pointerInput(image) {
                detectDragGestures(
                    onDragStart = { start ->
                        val fit = Fit(image, size.width.toFloat(), size.height.toFloat())
                        val reach = 40.dp.toPx()
                        // The picked outline's handles win, then corners of any outline, then sides.
                        val order = listOf(currentSelected).filter { it in currentQuads.indices } + currentQuads.indices.filter { it != currentSelected }
                        var best: Grab? = null
                        var bestD = reach
                        for (qi in order) {
                            val hs = OutlineEdit.handles(currentQuads[qi])
                            for ((h, pt) in hs.withIndex()) {
                                // Corners reach a little further than sides, so a small print's corners stay easy to catch.
                                val d = (fit.toScreen(pt) - start).getDistance() * (if (h < 4) 0.8f else 1f)
                                if (d < bestD) { bestD = d; best = Grab(qi, h) }
                            }
                            if (best != null && qi == currentSelected) break
                        }
                        grab = best
                        live = best?.let { currentQuads[it.quad] }
                        best?.let { if (it.quad != currentSelected) onSelect(it.quad) }
                    },
                    onDragEnd = {
                        val g = grab; val q = live
                        if (g != null && q != null) onChange(g.quad, q)
                        grab = null; live = null
                    },
                    onDragCancel = { grab = null; live = null },
                    onDrag = { change, delta ->
                        val g = grab ?: return@detectDragGestures
                        val q = live ?: return@detectDragGestures
                        change.consume()
                        val fit = Fit(image, size.width.toFloat(), size.height.toFloat())
                        live = OutlineEdit.drag(q, g.handle, Pt(delta.x / fit.scale, delta.y / fit.scale), image.width.toFloat(), image.height.toFloat())
                    },
                )
            },
    ) {
        val fit = Fit(image, size.width, size.height)
        drawImage(
            image,
            dstOffset = IntOffset(fit.ox.toInt(), fit.oy.toInt()),
            dstSize = IntSize((image.width * fit.scale).toInt(), (image.height * fit.scale).toInt()),
        )
        val paths = quads.indices.map { i -> pathOf(shown(i).points.map(fit::toScreen)) }
        // Dim everything that won't be kept.
        val kept = Path().apply { paths.forEach { addPath(it) } }
        clipPath(kept, clipOp = ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.5f)) }

        for (i in quads.indices) {
            val on = i == selected
            val colour = if (on) Palette.Accent else Color.White
            drawPath(paths[i], colour, style = Stroke((if (on) 2.5f else 1.5f).dp.toPx()))
            val hs = OutlineEdit.handles(shown(i)).map(fit::toScreen)
            for ((h, p) in hs.withIndex()) {
                val held = grab == Grab(i, h)
                if (h < 4) {
                    drawCircle(Color.White, radius = (if (held) 13f else if (on) 10f else 7f).dp.toPx(), center = p)
                    drawCircle(colour.takeIf { on } ?: Palette.Ink, radius = (if (on) 6f else 4f).dp.toPx(), center = p)
                } else if (on) {
                    // A short bar along the side: grab here to move the whole side.
                    val a = shown(i).points[h - 4]; val b = shown(i).points[(h - 3) % 4]
                    val dx = b.x - a.x; val dy = b.y - a.y
                    val len = hypot(dx, dy).coerceAtLeast(1f)
                    val half = Offset(dx / len, dy / len) * (if (held) 16f else 12f).dp.toPx()
                    drawLine(Color.White, p - half, p + half, 7.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(Palette.Accent, p - half, p + half, 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }

        val g = grab
        val q = live
        if (g != null && q != null) loupe(image, fit, q, OutlineEdit.handles(q)[g.handle], hs = OutlineEdit.handles(q).map(fit::toScreen)[g.handle])
    }
}

private fun pathOf(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    pts.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/** A circle in the top corner away from the finger, showing [at] magnified 3 times with the outline over it. */
private fun DrawScope.loupe(image: ImageBitmap, fit: Fit, q: Quad, at: Pt, hs: Offset) {
    val radius = 64.dp.toPx()
    val zoom = 3f
    val center = Offset(
        if (hs.x < size.width / 2) size.width - radius - 8.dp.toPx() else radius + 8.dp.toPx(),
        radius + 8.dp.toPx(),
    )
    val k = fit.scale * zoom
    val toLoupe = { p: Pt -> Offset(center.x + (p.x - at.x) * k, center.y + (p.y - at.y) * k) }
    val circle = Path().apply { addOval(Rect(center, radius)) }
    clipPath(circle) {
        drawRect(Palette.Ground, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
        // The part of the image under the loupe, clamped to the image.
        val half = radius / k
        val l = (at.x - half).toInt().coerceIn(0, image.width - 1)
        val t = (at.y - half).toInt().coerceIn(0, image.height - 1)
        val r = (at.x + half).toInt().coerceIn(l + 1, image.width)
        val b = (at.y + half).toInt().coerceIn(t + 1, image.height)
        val topLeft = toLoupe(Pt(l.toFloat(), t.toFloat()))
        drawImage(
            image,
            srcOffset = IntOffset(l, t),
            srcSize = IntSize(r - l, b - t),
            dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
            dstSize = IntSize(((r - l) * k).toInt(), ((b - t) * k).toInt()),
        )
        drawPath(pathOf(q.points.map(toLoupe)), Palette.Accent, style = Stroke(2.dp.toPx()))
    }
    drawCircle(Color.White, radius = radius, center = center, style = Stroke(2.dp.toPx()))
    drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
}
