package io.github.darkxace01.attic.world

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One album as a floating island: the closed album seen from above at an angle (cover board, the block of
 * page edges, the back board), a few of its own photos drifting round it, and its shadow far below.
 */
class Island(
    val key: Long,
    val cover: ImageBitmap?,
    /** Photos that drift round the island. A few is plenty. */
    val satellites: List<ImageBitmap>,
    /** How many pages: the thicker the album, the deeper its strata. */
    val pages: Int,
    /** The binding: cloth or leather, coloured after the album's photos. */
    val cloth: Color,
    /** Seen from the spine side instead of the fore-edge, so neighbours don't look stamped out. */
    val mirrored: Boolean,
    /** Landscape albums are wider than deep; some are square. */
    val aspect: Float = 0.74f,
)

/** A point in the island's own space: x right, y away from the viewer, z up. The album is about 1 wide. */
internal data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): V3 = (1f / sqrt(this dot this).coerceAtLeast(1e-6f)).let { this * it }
}

/**
 * A camera above and in front of the island, looking down at [pitchDeg], the island turned by [yawDeg].
 * [scale] is how many pixels one unit is at the island's centre.
 */
internal class Camera(center: Offset, val scale: Float, yawDeg: Float, pitchDeg: Float, private val distance: Float = 4.2f) {
    private val cx = center.x
    private val cy = center.y
    private val cyaw = cos(yawDeg * PI / 180).toFloat()
    private val syaw = sin(yawDeg * PI / 180).toFloat()
    private val cp = cos(pitchDeg * PI / 180).toFloat()
    private val sp = sin(pitchDeg * PI / 180).toFloat()

    /** World point, turned by the island's yaw. */
    private fun turn(p: V3) = V3(p.x * cyaw - p.y * syaw, p.x * syaw + p.y * cyaw, p.z)

    /** How far in front of the camera: bigger is further away. */
    fun depth(p: V3): Float {
        val t = turn(p)
        return t.y * cp - t.z * sp + distance
    }

    fun at(p: V3): Offset {
        val t = turn(p)
        val up = t.y * sp + t.z * cp
        val d = t.y * cp - t.z * sp + distance
        val f = scale * distance / d
        return Offset(cx + t.x * f, cy - up * f)
    }

    /** How big one unit looks at [p]. */
    fun sizeAt(p: V3): Float = scale * distance / depth(p)

    /** A face (corners in order) faces the camera. */
    fun faces(a: V3, b: V3, c: V3): Boolean {
        val pa = at(a); val pb = at(b); val pc = at(c)
        return (pb.x - pa.x) * (pc.y - pa.y) - (pb.y - pa.y) * (pc.x - pa.x) < 0
    }
}

/** The light: the attic window's beam, from the upper left, a little in front. */
private val LIGHT = V3(-0.55f, -0.35f, 0.76f).norm()

private val PAPER = Color(0xFFEDE3CC)
private val PAPER_DARK = Color(0xFFA8977A)

/**
 * Draws [island] centred at [center], [width] pixels across, at time [t] seconds: it bobs on its own
 * slow rhythm and its photos drift round it.
 */
internal fun DrawScope.drawIsland(island: Island, center: Offset, width: Float, t: Float, open: Float = 0f) {
    val seed = (island.key * 7919 % 1000).toFloat() / 1000f
    // Opening: the camera comes round square and looks down into the book as the cover swings open.
    val e = open.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    // Each island breathes at its own pace, never in step with its neighbours.
    val bobPeriod = 5.5f + seed * 2.5f
    val bob = sin((t / bobPeriod + seed) * 2 * PI).toFloat() * (1 - e)
    val yaw = ((if (island.mirrored) 1 else -1) * (24f + seed * 10f) + sin(t / 11f + seed * 6f) * 2.5f) * (1 - e)
    val pitch = (24f + seed * 5f) * (1 - e) + 82f * e
    val cam = Camera(center + Offset(0f, -bob * width * 0.025f), width * 0.46f, yaw, pitch)

    val w = 1f
    val d = w * island.aspect
    val pages = island.pages.coerceIn(1, 60)
    val block = 0.06f + 0.16f * min(pages, 40) / 40f      // the page block's thickness
    val board = 0.022f
    val over = 0.022f                                      // boards stand proud of the pages
    val bottom = -(block + 2 * board) / 2

    // Photos drifting round it: the ones behind first, the album, then the ones in front.
    val sats = island.satellites.take(4).mapIndexed { i, img ->
        val speed = 0.028f + 0.01f * ((i + seed * 3) % 1f)
        val a = (t * speed + i.toFloat() / island.satellites.size.coerceAtMost(4) + seed) * 2 * PI.toFloat()
        val r = 0.86f + 0.12f * ((i * 0.37f + seed) % 1f)
        val h = bottom + 0.1f + 0.38f * (((i * 0.61f + seed * 1.7f) % 1f)) + 0.05f * sin(t * 0.7f + i * 2f)
        Triple(img, V3(cos(a) * r, sin(a) * r * 0.82f, h), i)
    }
    val bookDepth = cam.depth(V3(0f, 0f, 0f))
    // The drifting photos make way as the album opens.
    val showSats = e < 0.3f
    if (showSats) sats.filter { cam.depth(it.second) > bookDepth }.sortedByDescending { cam.depth(it.second) }.forEach { (img, p, i) -> drawSatellite(cam, img, p, i, t, seed, behind = true) }

    // Underneath, the island's bedrock: the album's own prints and pages, layer on layer, narrowing to a point.
    if (e < 0.7f) drawUnderside(cam, w, d, bottom, island, seed)

    // Back board, the pages, the front board.
    val b0 = V3(-w / 2, -d / 2, bottom)
    drawBox(cam, b0, V3(w, d, board), island.cloth, null)
    drawPages(cam, V3(-w / 2 + 0.004f, -d / 2 + over, bottom + board), V3(w - over - 0.004f, d - 2 * over, block), seed, island.mirrored, if (e > 0f) island.cover else null)
    // The front board turns on the spine (the left edge), up and over to the left.
    val hinge = bottom + board + block
    val theta = (e * 178f) * PI.toFloat() / 180f
    val ct = cos(theta); val st = sin(theta)
    val swing = { p: V3 -> val dx = p.x + w / 2; val dz = p.z - hinge; V3(-w / 2 + dx * ct - dz * st, p.y, hinge + dx * st + dz * ct) }
    drawBox(cam, V3(-w / 2, -d / 2, hinge), V3(w, d, board), island.cloth, island.cover, xf = swing, inside = ENDPAPER)
    if (e < 0.5f) drawRibbon(cam, w, d, hinge, bottom, t, seed, island.mirrored)

    if (showSats) sats.filter { cam.depth(it.second) <= bookDepth }.sortedByDescending { cam.depth(it.second) }.forEach { (img, p, i) -> drawSatellite(cam, img, p, i, t, seed, behind = false) }
}

/** The inside of the cover: the endpaper, a deep warm grey. */
private val ENDPAPER = Color(0xFF8C8273)

/**
 * The underside: slabs of paper and prints, each a little smaller and turned a little, hanging below the
 * album like the rock under a floating island. Some are plain pages, some white-bordered prints, some
 * take a photo's colour.
 */
private fun DrawScope.drawUnderside(cam: Camera, w: Float, d: Float, bottom: Float, island: Island, seed: Float) {
    val layers = 9
    val colours = island.satellites.map { averageColour(it) } + island.cover?.let { listOf(averageColour(it)) }.orEmpty()
    // Each layer sits right under the one above, smaller: a stepped cone of paper hanging to a point.
    val slabs = (0 until layers).map { k ->
        val f = (k + 1).toFloat() / layers
        // Wide near the top, then narrowing faster: the shape of the rock under a floating island.
        val shrink = 0.06f + 0.9f * (1f - Math.pow(f.toDouble(), 1.6).toFloat())
        val sw = w * shrink * (0.9f + 0.12f * frac(seed * 3 + k * 0.37f))
        val sd = d * shrink * (0.88f + 0.14f * frac(seed * 5 + k * 0.53f))
        val h = 0.065f + 0.035f * frac(seed * 7 + k * 0.29f)
        val dx = (frac(seed * 11 + k * 0.61f) - 0.5f) * 0.1f * f
        val dy = (frac(seed * 13 + k * 0.47f) - 0.5f) * 0.08f * f
        val turn = (frac(seed * 17 + k * 0.71f) - 0.5f) * 22f
        val kind = frac(seed * 19 + k * 0.83f)
        val colour = when {
            kind < 0.5f -> lerp(PAPER, PAPER_DARK, 0.15f + 0.45f * frac(k * 0.37f + seed))
            kind < 0.72f || colours.isEmpty() -> Color(0xFFF4F1EA)
            else -> lerp(colours[k % colours.size], Color.Black, 0.2f)
        }
        listOf(dx, dy, sw, sd, h, turn, if (kind < 0.5f) 4f else 0f) to colour
    }
    var z = bottom
    val placed = slabs.map { (g, colour) -> z -= g[4]; Triple(g, colour, z) }
    // From the lowest (smallest, furthest) up, so nearer ones cover them.
    for ((g, colour, zz) in placed.reversed()) {
        drawBox(cam, V3(g[0] - g[2] / 2, g[1] - g[3] / 2, zz), V3(g[2], g[3], g[4]), colour, null, g[5], leaves = g[6].toInt())
    }
}

/** The bookmark: a ribbon in the studio's pencil red, out from between the pages and down past the edge, swaying. */
private fun DrawScope.drawRibbon(cam: Camera, w: Float, d: Float, top: Float, bottom: Float, t: Float, seed: Float, mirrored: Boolean) {
    val x = if (mirrored) -w * 0.36f else w * 0.36f
    val start = V3(x, -d / 2 + 0.03f, top - 0.004f)
    val sway = sin(t * 0.9f + seed * 4f) * 0.035f
    val pts = (0..12).map { i ->
        val f = i / 12f
        // Over the front edge, then straight down, the end swinging more than the top.
        if (f < 0.15f) start + V3(0f, -0.06f * (f / 0.15f), 0f)
        else V3(x + sway * (f * f) * 2.2f, -d / 2 - 0.035f - 0.015f * f, top - (f - 0.15f) * 0.42f)
    }.map(cam::at)
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    val wide = cam.sizeAt(start) * 0.035f
    drawPath(path, RIBBON_DARK, style = Stroke(wide + 1.5f, cap = androidx.compose.ui.graphics.StrokeCap.Butt))
    drawPath(path, RIBBON, style = Stroke(wide, cap = androidx.compose.ui.graphics.StrokeCap.Butt))
    // The cut end: a small notch.
    val end = pts.last()
    val before = pts[pts.size - 2]
    val dir = (end - before).let { it / it.getDistance().coerceAtLeast(1f) }
    drawLine(ATTIC, end - Offset(-dir.y, dir.x) * (wide / 2), end - dir * (wide * 0.6f), wide * 0.45f)
    drawLine(ATTIC, end + Offset(-dir.y, dir.x) * (wide / 2), end - dir * (wide * 0.6f), wide * 0.45f)
}

/** The studio's pencil red. */
private val RIBBON = Color(0xFFE5553A)
private val RIBBON_DARK = Color(0xFF8E2A1A)

private fun frac(v: Float) = v - kotlin.math.floor(v)

/** A photo's average colour, from a few sample points. */
private fun averageColour(img: ImageBitmap): Color {
    val bmp = img.asAndroidBitmap()
    var r = 0f; var g = 0f; var b = 0f; var n = 0
    for (i in 1..5) for (j in 1..5) {
        val px = bmp.getPixel(bmp.width * i / 6, bmp.height * j / 6)
        r += android.graphics.Color.red(px); g += android.graphics.Color.green(px); b += android.graphics.Color.blue(px); n++
    }
    return Color(r / n / 255f, g / n / 255f, b / n / 255f)
}

/** A board of the binding: a flat box in [cloth]. The front board carries the cover photo in a window. */
private fun DrawScope.drawBox(
    cam: Camera, o: V3, s: V3, cloth: Color, cover: ImageBitmap?, turnDeg: Float = 0f, leaves: Int = 0,
    xf: (V3) -> V3 = { it }, inside: Color? = null,
) {
    val c = corners(o, s, turnDeg).map(xf)
    var coverShows = false
    for ((index, fn) in faces(c).withIndex()) {
        val (face, normal) = fn
        if (!cam.faces(face[0], face[1], face[2])) continue
        if (index == 4) coverShows = true
        fillQuad(cam, face, shade(if (index == 5 && inside != null) inside else cloth, normal))
        // A stack of paper shows its leaves along the sides.
        if (leaves > 0 && normal.z < 0.5f) {
            for (k in 1..leaves) {
                val f = k.toFloat() / (leaves + 1)
                drawLine(shade(lerp(cloth, PAPER_DARK, 0.6f), normal).copy(alpha = 0.7f), cam.at(face[0] + (face[3] - face[0]) * f), cam.at(face[1] + (face[2] - face[1]) * f), 0.8f)
            }
        }
    }
    // The top of the front board: the cover.
    if (cover != null && coverShows) {
        val top = o.z + s.z
        val inset = 0.17f
        val win = listOf(
            V3(o.x + s.x * inset, o.y + s.y * 0.78f, top), V3(o.x + s.x * (1 - inset), o.y + s.y * 0.78f, top),
            V3(o.x + s.x * (1 - inset), o.y + s.y * 0.2f, top), V3(o.x + s.x * inset, o.y + s.y * 0.2f, top),
        )
        // A window pressed into the cloth, the photo behind it.
        val pressed = win.map { p -> p + (p - V3(o.x + s.x / 2, o.y + s.y / 2, top)) * 0.06f }.map(xf)
        fillQuad(cam, pressed, lerp(cloth, Color.Black, 0.35f))
        drawImageOnQuad(cam, cover, win.map(xf))
        drawPath(pathOf(win.map(xf).map(cam::at)), Color.Black.copy(alpha = 0.35f), style = Stroke(1.2f))
        // A short pressed line under the window, where a title would be stamped.
        val l1 = cam.at(xf(V3(o.x + s.x * 0.36f, o.y + s.y * 0.11f, top)))
        val l2 = cam.at(xf(V3(o.x + s.x * 0.64f, o.y + s.y * 0.11f, top)))
        drawLine(lerp(cloth, Color.Black, 0.3f), l1, l2, 2.2f)
        drawLine(lerp(cloth, Color.White, 0.18f), l1 + Offset(0f, 1.6f), l2 + Offset(0f, 1.6f), 1f)
    }
}

/**
 * The page block: paper edges in fine layers like rock strata, a little uneven, with the edges of a few
 * prints showing between the pages.
 */
private fun DrawScope.drawPages(cam: Camera, o: V3, s: V3, seed: Float, mirrored: Boolean, firstPage: ImageBitmap?) {
    val c = corners(o, s)
    for ((face, normal) in faces(c)) {
        if (normal.z < -0.5f || !cam.faces(face[0], face[1], face[2])) continue
        if (normal.z > 0.5f) {
            // The first page, under the cover: paper with the first photo on it.
            fillQuad(cam, face, shade(PAPER, normal))
            if (firstPage != null) {
                val top = o.z + s.z
                val aspect = firstPage.width.toFloat() / firstPage.height.coerceAtLeast(1)
                val ph = s.y * 0.62f
                val pw = (ph * aspect).coerceAtMost(s.x * 0.7f)
                val cx = o.x + s.x / 2; val cy = o.y + s.y / 2
                val border = 0.018f
                fillQuad(cam, listOf(V3(cx - pw / 2 - border, cy + ph / 2 + border, top), V3(cx + pw / 2 + border, cy + ph / 2 + border, top), V3(cx + pw / 2 + border, cy - ph / 2 - border, top), V3(cx - pw / 2 - border, cy - ph / 2 - border, top)), Color(0xFFF7F4EE))
                drawImageOnQuad(cam, firstPage, listOf(V3(cx - pw / 2, cy + ph / 2, top), V3(cx + pw / 2, cy + ph / 2, top), V3(cx + pw / 2, cy - ph / 2, top), V3(cx - pw / 2, cy - ph / 2, top)))
            }
            continue
        }
        val base = shade(PAPER, normal)
        fillQuad(cam, face, base)
        // face corners: bottom a, bottom b, top b, top a (see faces()).
        val a0 = face[0]; val b0 = face[1]; val b1 = face[2]; val a1 = face[3]
        val layers = (s.z / 0.006f).toInt().coerceIn(6, 40)
        for (k in 1 until layers) {
            val f = k.toFloat() / layers
            val wob = ((k * 0.618f + seed) % 1f)
            val tone = shade(lerp(PAPER, PAPER_DARK, 0.35f + 0.65f * wob), normal).copy(alpha = 0.75f)
            val p = cam.at(a0 + (a1 - a0) * f)
            val q = cam.at(b0 + (b1 - b0) * f)
            drawLine(tone, p, q, 0.8f)
        }
        // A few prints between the pages: a sliver of white border or colour, poking out.
        repeat(3) { k ->
            val f = 0.15f + 0.7f * (((k * 0.43f + seed * 2.3f) % 1f))
            val from = 0.1f + 0.6f * (((k * 0.71f + seed) % 1f))
            val len = 0.12f + 0.2f * (((k * 0.29f + seed * 1.3f) % 1f))
            val p = cam.at(a0 + (b0 - a0) * from + (a1 - a0) * f)
            val q = cam.at(a0 + (b0 - a0) * (from + len).coerceAtMost(0.95f) + (a1 - a0) * f)
            drawLine(shade(Color(0xFFF7F4EE), normal), p, q, 1.6f)
        }
    }
}

/** A print drifting round the island: it always turns its face to you, rocking a little as it floats. */
private fun DrawScope.drawSatellite(cam: Camera, img: ImageBitmap, p: V3, i: Int, t: Float, seed: Float, behind: Boolean) {
    val size = cam.sizeAt(p) * 0.25f
    val aspect = img.height.toFloat() / img.width.coerceAtLeast(1)
    val wPx = if (aspect > 1) size / aspect else size
    val hPx = wPx * aspect
    val c = cam.at(p)
    val angle = (sin(t * 0.5f + i * 1.7f + seed * 5f) * 9f + (i - 1.5f) * 6f) * PI.toFloat() / 180f
    val border = wPx * 0.06f
    fun rot(dx: Float, dy: Float) = Offset(c.x + dx * cos(angle) - dy * sin(angle), c.y + dx * sin(angle) + dy * cos(angle))
    val hw = wPx / 2 + border; val hh = hPx / 2 + border
    // Its own small shadow, cast down and away from the light.
    val sh = listOf(rot(-hw, -hh), rot(hw, -hh), rot(hw, hh), rot(-hw, hh)).map { it + Offset(size * 0.08f, size * 0.14f) }
    drawPath(pathOf(sh), Color.Black.copy(alpha = if (behind) 0.18f else 0.3f))
    drawPath(pathOf(listOf(rot(-hw, -hh), rot(hw, -hh), rot(hw, hh), rot(-hw, hh))), Color(0xFFF4F1EA))
    val m = android.graphics.Matrix()
    val src = floatArrayOf(0f, 0f, img.width.toFloat(), 0f, img.width.toFloat(), img.height.toFloat(), 0f, img.height.toFloat())
    val dst = listOf(rot(-wPx / 2, -hPx / 2), rot(wPx / 2, -hPx / 2), rot(wPx / 2, hPx / 2), rot(-wPx / 2, hPx / 2)).flatMap { listOf(it.x, it.y) }.toFloatArray()
    m.setPolyToPoly(src, 0, dst, 0, 4)
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
        if (behind) paint.alpha = 225
        canvas.nativeCanvas.drawBitmap(img.asAndroidBitmap(), m, paint)
    }
}

// ---- geometry ----

/** The 8 corners of a box from [o] with size [s]: bottom 0..3, top 4..7, both counter-clockwise from above. */
private fun corners(o: V3, s: V3, turnDeg: Float = 0f): List<V3> {
    val x0 = o.x; val x1 = o.x + s.x; val y0 = o.y; val y1 = o.y + s.y; val z0 = o.z; val z1 = o.z + s.z
    val pts = listOf(V3(x0, y0, z0), V3(x1, y0, z0), V3(x1, y1, z0), V3(x0, y1, z0), V3(x0, y0, z1), V3(x1, y0, z1), V3(x1, y1, z1), V3(x0, y1, z1))
    if (turnDeg == 0f) return pts
    val cx = o.x + s.x / 2; val cy = o.y + s.y / 2
    val c = cos(turnDeg * PI / 180).toFloat(); val sn = sin(turnDeg * PI / 180).toFloat()
    return pts.map { p -> V3(cx + (p.x - cx) * c - (p.y - cy) * sn, cy + (p.x - cx) * sn + (p.y - cy) * c, p.z) }
}

/** The box's faces, each as bottom-left, bottom-right, top-right, top-left seen from outside, with its normal. */
private fun faces(c: List<V3>): List<Pair<List<V3>, V3>> = listOf(
    listOf(c[0], c[1], c[5], c[4]),   // front (towards the viewer)
    listOf(c[1], c[2], c[6], c[5]),   // right
    listOf(c[2], c[3], c[7], c[6]),   // back
    listOf(c[3], c[0], c[4], c[7]),   // left
    listOf(c[4], c[5], c[6], c[7]),   // top
    listOf(c[0], c[3], c[2], c[1]),   // bottom
).map { f -> f to (f[1] - f[0]).cross(f[3] - f[0]).norm() }

private fun shade(base: Color, normal: V3): Color {
    val k = 0.52f + 0.55f * max(0f, normal dot LIGHT)
    return Color(min(1f, base.red * k), min(1f, base.green * k), min(1f, base.blue * k), base.alpha)
}

private fun pathOf(pts: List<Offset>) = Path().apply {
    moveTo(pts[0].x, pts[0].y)
    pts.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

private fun DrawScope.fillQuad(cam: Camera, q: List<V3>, color: Color) = drawPath(pathOf(q.map(cam::at)), color)

/** [img] mapped onto a quad in space (corners: top-left, top-right, bottom-right, bottom-left of the image). */
private fun DrawScope.drawImageOnQuad(cam: Camera, img: ImageBitmap, q: List<V3>) {
    // Crop the image to the window's shape so it fills it without stretching.
    val win = q.map(cam::at)
    val ww = (win[1] - win[0]).getDistance().coerceAtLeast(1f)
    val wh = (win[3] - win[0]).getDistance().coerceAtLeast(1f)
    val iw = img.width.toFloat(); val ih = img.height.toFloat()
    val want = ww / wh
    val (sw, sh) = if (iw / ih > want) ih * want to ih else iw to iw / want
    val sx = (iw - sw) / 2; val sy = (ih - sh) / 2
    val src = floatArrayOf(sx, sy, sx + sw, sy, sx + sw, sy + sh, sx, sy + sh)
    val dst = win.flatMap { listOf(it.x, it.y) }.toFloatArray()
    val m = android.graphics.Matrix()
    m.setPolyToPoly(src, 0, dst, 0, 4)
    drawIntoCanvas { canvas ->
        val save = canvas.nativeCanvas.save()
        val clip = android.graphics.Path().apply {
            moveTo(win[0].x, win[0].y); win.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        canvas.nativeCanvas.clipPath(clip)
        canvas.nativeCanvas.drawBitmap(img.asAndroidBitmap(), m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        canvas.nativeCanvas.restoreToCount(save)
    }
}

/** For screenshot tests: one island drawn at a moment of its opening. */
internal fun drawIslandForTest(scope: DrawScope, island: Island, center: Offset, width: Float, t: Float, open: Float) =
    scope.drawIsland(island, center, width, t, open)
