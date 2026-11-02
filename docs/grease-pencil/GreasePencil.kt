package greasepencil

// Grease Pencil signature kit: the hand-drawn red loop that goes in every app.
//
// Copy this one file into any Jetpack Compose app. It only needs Compose UI and foundation (no Material,
// no other libraries). You can keep the package name or change it to your app's.
//
//   GreasePencilLoop(Modifier.size(48.dp))                  a loop that draws itself in
//   Text("found", Modifier.circled())                       a loop around anything, behind it
//   PromiseLine(listOf("No internet", "No ads"), "1.0.0")   the footer for the end of Settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object GreasePencil {
    /** The pencil red. Use it as the accent in every app, even when nothing else matches. */
    val Red = Color(0xFFD4432A)

    /** The same red, a touch brighter, so it holds up on dark backgrounds. */
    val RedOnDark = Color(0xFFE5553A)

    /** [Red] or [RedOnDark], following the system's light or dark mode. */
    val red: Color
        @Composable get() = if (isSystemInDarkTheme()) RedOnDark else Red

    /** How long the loop takes to draw itself in. Quick, like a real flick of the wrist. */
    const val DRAW_MILLIS = 650
}

/**
 * The loop's shape: a slightly lopsided, tilted oval that overshoots where it started, like a quick
 * circle drawn by hand around a keeper on a contact sheet. It fills [size].
 */
fun greasePencilLoopPath(size: Size): Path = Path().apply {
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val rx = size.width * 0.41f
    val ry = size.height * 0.37f
    val tilt = -0.14f
    val steps = 80
    val sweep = 2.0 * PI * 1.12 // a little more than once round: the overshoot
    val startAngle = -PI * 0.62
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val a = startAngle + sweep * t
        // Uneven pressure, and the hand drifts outward as it comes round.
        val wobble = 1f + 0.035f * sin(2.0 * a + 0.7).toFloat() + 0.07f * t
        val x = cos(a).toFloat() * rx * wobble
        val y = sin(a).toFloat() * ry * wobble
        val px = cx + x * cos(tilt) - y * sin(tilt)
        val py = cy + x * sin(tilt) + y * cos(tilt)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
}

/**
 * Draws the loop over (or, with [behind], under) whatever this modifier is on, revealed up to
 * [progress] (0 to 1). [strokeWidth] is a fraction of the smaller side.
 */
fun Modifier.greasePencilLoop(
    color: Color = GreasePencil.Red,
    progress: () -> Float = { 1f },
    strokeWidth: Float = 0.06f,
    behind: Boolean = false,
): Modifier = drawWithCache {
    val path = greasePencilLoopPath(size)
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    val width = min(size.width, size.height) * strokeWidth
    onDrawWithContent {
        if (!behind) drawContent()
        drawPencilStroke(measure, segment, progress().coerceIn(0f, 1f), color, width)
        if (behind) drawContent()
    }
}

/** One waxy grease-pencil line: a main stroke plus a faint, slightly offset second one. */
private fun DrawScope.drawPencilStroke(measure: PathMeasure, segment: Path, progress: Float, color: Color, width: Float) {
    if (progress <= 0f) return
    segment.reset()
    measure.getSegment(0f, measure.length * progress, segment, true)
    fun line(c: Color, w: Float) = drawPath(segment, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
    line(color.copy(alpha = color.alpha * 0.92f), width)
    translate(width * 0.18f, -width * 0.12f) { line(color.copy(alpha = color.alpha * 0.35f), width * 0.55f) }
}

/**
 * The loop on its own. With [animate] it draws itself in when it first appears; use that for the one
 * moment per app that deserves it (a task done, something saved, a thank-you). Change [key] to play it again.
 */
@Composable
fun GreasePencilLoop(
    modifier: Modifier = Modifier,
    color: Color = GreasePencil.red,
    strokeWidth: Float = 0.06f,
    animate: Boolean = true,
    key: Any? = Unit,
) {
    val progress = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) {
        if (animate) progress.animateTo(1f, tween(GreasePencil.DRAW_MILLIS, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.greasePencilLoop(color, { progress.value }, strokeWidth)) {}
}

/**
 * Circles whatever it's on, the way Sweepy circles a word in a headline: the loop is drawn behind
 * the content and well outside it, so it goes round the letters and never through them. It spills
 * past the content's edges, so leave a little room around whatever you circle.
 */
fun Modifier.circled(
    color: Color = GreasePencil.Red,
    progress: () -> Float = { 1f },
    strokeWidth: Float = 0.06f,
): Modifier = drawWithCache {
    // Big enough that the tilted oval clears the corners of the content, centred on it.
    val grown = Size(size.width * 1.35f + size.height * 0.6f, size.height * 2.1f)
    val path = greasePencilLoopPath(grown)
    val measure = PathMeasure().apply { setPath(path, false) }
    val segment = Path()
    val width = min(grown.width, grown.height) * strokeWidth
    onDrawWithContent {
        translate((size.width - grown.width) / 2, (size.height - grown.height) / 2) {
            drawPencilStroke(measure, segment, progress().coerceIn(0f, 1f), color, width)
        }
        drawContent()
    }
}

/**
 * The line that ends every Settings screen: a small loop, then the app's honest promises,
 * e.g. "Sweepy 1.0.0 · No internet · No ads · No account". Keep the promises short and true.
 */
@Composable
fun PromiseLine(
    promises: List<String>,
    version: String,
    modifier: Modifier = Modifier,
    appName: String? = null,
    color: Color = Color.Gray,
    loopColor: Color = color.copy(alpha = 0.6f),
    style: TextStyle = TextStyle(fontSize = 10.sp, letterSpacing = 0.8.sp),
    /** Leave it off when the studio logo (GreasePencilStudio.kt) sits right above: one loop is enough. */
    showLoop: Boolean = true,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showLoop) {
            GreasePencilLoop(Modifier.size(20.dp), color = loopColor, strokeWidth = 0.1f, animate = false)
            Spacer(Modifier.height(8.dp))
        }
        val first = listOfNotNull(appName, version).joinToString(" ")
        BasicText(
            (listOf(first) + promises).joinToString(" · ").uppercase(),
            style = style.copy(color = color, textAlign = TextAlign.Center),
        )
    }
}
