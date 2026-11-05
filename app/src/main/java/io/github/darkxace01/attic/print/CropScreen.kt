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
import io.github.darkxace01.attic.finalOutline
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.printNet
import io.github.darkxace01.attic.ui.OutlineEditor
import io.github.darkxace01.attic.ui.PrimaryButton
import io.github.darkxace01.attic.ui.QuietButton
import io.github.darkxace01.attic.ui.TopBar
import io.github.darkxace01.attic.ui.theme.Palette
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
    val net = LocalContext.current.printNet
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
            if (o != null && q != null) OutlineEditor(o.image, listOf(q), selected = 0, onSelect = {}, onChange = { _, nq -> quad = nq }, modifier = Modifier.fillMaxSize())
            else Text("Loading the original…", color = Palette.Muted, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            "Drag the corners or sides onto the edges of the print.",
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
                        net.finalOutline(o.full, fullQuad, edgeSearch = 0.02f)
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
