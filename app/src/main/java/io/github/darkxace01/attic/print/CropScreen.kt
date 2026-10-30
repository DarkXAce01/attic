package io.github.darkxace01.attic.print

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.vision.EdgeFit
import io.github.darkxace01.attic.vision.PrintDetector
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

private class Original(val image: ImageBitmap, val scale: Float, val full: Mat)

/**
 * Drag the corners onto the print's real corners on the untouched original photo. While dragging, a
 * loupe shows the corner magnified, because a finger covers exactly the spot you're placing.
 */
@Composable
fun CropScreen(printId: Long, onDone: () -> Unit) {
    val library = LocalContext.current.library
    val scope = rememberCoroutineScope()
    var print by remember { mutableStateOf<PrintEntity?>(null) }
    var original by remember { mutableStateOf<Original?>(null) }
    var quad by remember { mutableStateOf<Quad?>(null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(printId) {
        val p = withContext(Dispatchers.IO) { library.db.dao().print(printId) } ?: return@LaunchedEffect
        val full = library.uprightOriginal(p) ?: return@LaunchedEffect
        val o = withContext(Dispatchers.Default) {
            val scale = min(1f, 1800f / max(full.cols(), full.rows()))
            val small = Mat()
            Imgproc.resize(full, small, org.opencv.core.Size(full.cols() * scale.toDouble(), full.rows() * scale.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val bmp = Bitmap.createBitmap(small.cols(), small.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(small, bmp)
            small.release()
            Original(bmp.asImageBitmap(), scale, full)
        }
        print = p
        original = o
        quad = p.cropQuad.scale(o.scale, o.scale)
    }

    DisposableEffect(Unit) { onDispose { original?.full?.release() } }

    Column(Modifier.fillMaxSize()) {
        TopBar("Fix crop", onBack = onDone)
        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp)) {
            val o = original
            val q = quad
            if (o != null && q != null) CornerEditor(o.image, q, onChange = { quad = it })
            else Text("Loading the original…", color = Palette.Muted, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            "Drag each corner onto the corner of the print.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.Muted,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QuietButton("Snap to edges", onClick = {
                val o = original ?: return@QuietButton
                val q = quad ?: return@QuietButton
                scope.launch {
                    val snapped = withContext(Dispatchers.Default) {
                        val fullQuad = q.scale(1f / o.scale, 1f / o.scale)
                        PrintDetector().snapEdges(o.full, fullQuad, (EdgeFit.diagonal(fullQuad) * 0.02f).coerceAtLeast(6f))
                    }
                    quad = snapped.scale(o.scale, o.scale)
                }
            })
            Spacer(Modifier.width(8.dp))
            PrimaryButton(
                if (saving) "Saving…" else "Save crop",
                enabled = !saving && original != null,
                modifier = Modifier.weight(1f),
                onClick = {
                    val o = original ?: return@PrimaryButton
                    val q = quad ?: return@PrimaryButton
                    saving = true
                    scope.launch {
                        library.recrop(printId, q.scale(1f / o.scale, 1f / o.scale))
                        library.export(printId)
                        onDone()
                    }
                },
            )
        }
    }
}

@Composable
private fun CornerEditor(image: ImageBitmap, quad: Quad, onChange: (Quad) -> Unit) {
    var dragging by remember { mutableIntStateOf(-1) }
    var current by remember { mutableStateOf(quad) }
    LaunchedEffect(quad) { current = quad }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(image) {
                val fit = min(size.width / image.width.toFloat(), size.height / image.height.toFloat())
                val ox = (size.width - image.width * fit) / 2
                val oy = (size.height - image.height * fit) / 2
                fun toScreen(p: Pt) = Offset(ox + p.x * fit, oy + p.y * fit)
                detectDragGestures(
                    onDragStart = { start ->
                        dragging = current.points.indices.minBy { (toScreen(current.points[it]) - start).getDistance() }
                            .takeIf { (toScreen(current.points[it]) - start).getDistance() < 64.dp.toPx() } ?: -1
                    },
                    onDragEnd = { dragging = -1; onChange(current) },
                    onDragCancel = { dragging = -1 },
                    onDrag = { change, delta ->
                        if (dragging < 0) return@detectDragGestures
                        change.consume()
                        val pts = current.points.toMutableList()
                        val p = pts[dragging]
                        pts[dragging] = Pt(
                            (p.x + delta.x / fit).coerceIn(0f, image.width.toFloat()),
                            (p.y + delta.y / fit).coerceIn(0f, image.height.toFloat()),
                        )
                        current = Quad(pts[0], pts[1], pts[2], pts[3])
                    },
                )
            },
    ) {
        val fit = min(size.width / image.width, size.height / image.height)
        val ox = (size.width - image.width * fit) / 2
        val oy = (size.height - image.height * fit) / 2
        fun toScreen(p: Pt) = Offset(ox + p.x * fit, oy + p.y * fit)

        drawImage(
            image,
            dstOffset = IntOffset(ox.toInt(), oy.toInt()),
            dstSize = IntSize((image.width * fit).toInt(), (image.height * fit).toInt()),
        )
        val pts = current.points.map(::toScreen)
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        // Dim everything outside the crop.
        clipPath(path, clipOp = androidx.compose.ui.graphics.ClipOp.Difference) {
            drawRect(Color.Black.copy(alpha = 0.55f))
        }
        drawPath(path, Palette.Accent, style = Stroke(2.dp.toPx()))
        pts.forEachIndexed { i, p ->
            drawCircle(Color.White, radius = if (i == dragging) 14.dp.toPx() else 11.dp.toPx(), center = p)
            drawCircle(Palette.Accent, radius = 7.dp.toPx(), center = p)
        }

        if (dragging >= 0) {
            // Loupe in the opposite top corner from the finger, 3× magnified.
            val corner = current.points[dragging]
            val radius = 64.dp.toPx()
            val zoom = 3f
            val center = Offset(
                if (pts[dragging].x < size.width / 2) size.width - radius - 8.dp.toPx() else radius + 8.dp.toPx(),
                radius + 8.dp.toPx(),
            )
            val srcHalf = radius / (fit * zoom)
            val src = Rect(corner.x - srcHalf, corner.y - srcHalf, corner.x + srcHalf, corner.y + srcHalf)
            val circle = Path().apply { addOval(Rect(center, radius)) }
            clipPath(circle) {
                drawRect(Palette.Ground, topLeft = center - Offset(radius, radius), size = Size(radius * 2, radius * 2))
                drawImage(
                    image,
                    srcOffset = IntOffset(src.left.toInt().coerceAtLeast(0), src.top.toInt().coerceAtLeast(0)),
                    srcSize = IntSize(
                        (src.width.toInt()).coerceAtMost(image.width - src.left.toInt().coerceAtLeast(0)),
                        (src.height.toInt()).coerceAtMost(image.height - src.top.toInt().coerceAtLeast(0)),
                    ),
                    dstOffset = IntOffset(
                        (center.x - radius + (src.left.coerceAtLeast(0f) - src.left) * fit * zoom).toInt(),
                        (center.y - radius + (src.top.coerceAtLeast(0f) - src.top) * fit * zoom).toInt(),
                    ),
                    dstSize = IntSize(
                        ((src.width.toInt()).coerceAtMost(image.width - src.left.toInt().coerceAtLeast(0)) * fit * zoom).toInt(),
                        ((src.height.toInt()).coerceAtMost(image.height - src.top.toInt().coerceAtLeast(0)) * fit * zoom).toInt(),
                    ),
                )
                // The crop edges, magnified, so you can see exactly where the corner sits.
                val toLoupe = { p: Pt -> Offset(center.x + (p.x - corner.x) * fit * zoom, center.y + (p.y - corner.y) * fit * zoom) }
                val lp = current.points.map(toLoupe)
                drawLine(Palette.Accent, lp[dragging], lp[(dragging + 1) % 4], 2.dp.toPx())
                drawLine(Palette.Accent, lp[dragging], lp[(dragging + 3) % 4], 2.dp.toPx())
            }
            drawCircle(Color.White, radius = radius, center = center, style = Stroke(2.dp.toPx()))
            drawCircle(Color.White, radius = 3.dp.toPx(), center = center)
        }
    }
}
