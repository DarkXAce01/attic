package io.github.darkxace01.attic.world.riso

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.darkxace01.attic.ui.theme.Ink
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.ui.theme.Riso
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** What an album's island is: a book on its own ground, a mountain, a branch, a balloon… */
enum class IslandKind(val label: String) {
    Book("Album"), Mountain("Mountain"), Branch("Branch"), Balloon("Balloon"), Kite("Kite"), Boat("Paper boat"),
    Cloud("Cloud"), Moon("Moon"), Tree("Tree"), Lighthouse("Lighthouse"), Tent("Campsite"), Desert("Desert"), Pond("Pond"),
}

/** Little things in the air round an island. */
enum class Extra(val label: String) { None("Nothing"), Birds("Birds"), Stars("Stars"), Confetti("Confetti"), Fireflies("Fireflies") }

/** How an album's title is set beside its island. */
enum class TitleStyle(val label: String) { Printed("Printed"), Pencil("Grease pencil"), Tape("Label tape") }

/**
 * How an album looks in the world. Saved with the album as a short string, so adding a choice later
 * never breaks an old one.
 */
data class IslandLook(
    val kind: IslandKind,
    val a: Ink,
    val b: Ink,
    val title: TitleStyle = TitleStyle.Printed,
    val extra: Extra = Extra.None,
    /** The print shown first (on the cover, the kite, the sail); null: the album's first. */
    val cover: Long? = null,
) {
    fun encode(): String = "kind=${kind.name};a=${a.name};b=${b.name};title=${title.name};extra=${extra.name}" + (cover?.let { ";cover=$it" } ?: "")

    companion object {
        /** Ink pairs that print well together, for albums nobody has dressed yet. */
        val PAIRS = listOf(
            Ink.Blue to Ink.Pink, Ink.Teal to Ink.Orange, Ink.Purple to Ink.Yellow, Ink.Green to Ink.Red,
            Ink.Burgundy to Ink.Aqua, Ink.Blue to Ink.Sunflower, Ink.Red to Ink.Teal, Ink.Black to Ink.Pink,
        )

        /** A different, steady look for each album until someone chooses one. */
        fun default(albumId: Long): IslandLook {
            val k = IslandKind.entries
            val i = Math.floorMod(albumId * 7 + 3, k.size.toLong()).toInt()
            val (a, b) = PAIRS[Math.floorMod(albumId * 5 + 1, PAIRS.size.toLong()).toInt()]
            return IslandLook(k[i], a, b)
        }

        fun parse(s: String?, albumId: Long): IslandLook {
            val d = default(albumId)
            if (s.isNullOrBlank()) return d
            val m = s.split(';').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            fun <E : Enum<E>> pick(values: Array<E>, key: String, fallback: E) = values.firstOrNull { it.name == m[key] } ?: fallback
            return IslandLook(
                kind = pick(IslandKind.entries.toTypedArray(), "kind", d.kind),
                a = pick(Ink.entries.toTypedArray(), "a", d.a),
                b = pick(Ink.entries.toTypedArray(), "b", d.b),
                title = pick(TitleStyle.entries.toTypedArray(), "title", d.title),
                extra = pick(Extra.entries.toTypedArray(), "extra", d.extra),
                cover = m["cover"]?.toLongOrNull(),
            )
        }
    }
}

/**
 * Draws an island centred on [c], about 2 [u] wide, at time [t] seconds (every motion comes from [t], so
 * screenshots can freeze it). [photos]: the album's own prints, the cover first. [seed] keeps
 * neighbouring islands from moving in step.
 */
fun DrawScope.drawIsland(look: IslandLook, photos: List<ImageBitmap>, c: Offset, u: Float, t: Float, seed: Int = 0) {
    IslandPainter(this, look, photos, c, u, t, seed).draw()
}

private class IslandPainter(
    val s: DrawScope,
    val look: IslandLook,
    val photos: List<ImageBitmap>,
    val c: Offset,
    val u: Float,
    val t: Float,
    seed: Int,
) {
    val A = look.a.color
    val B = look.b.color
    val ph = seed * 1.7f
    val bob = sin(t * 2 * PI.toFloat() / 6f + ph) * 0.045f * u
    /** The second pass, landing a little off register and drifting. */
    val mis = Offset((0.012f + 0.008f * sin(t * 0.8f + ph)) * u, (-0.008f + 0.006f * cos(t * 0.6f + ph)) * u)
    val cell = max(5f, u * 0.042f)

    fun p(x: Float, y: Float) = Offset(c.x + x * u, c.y + y * u + bob)
    fun len(v: Float) = v * u

    fun poly(vararg xy: Float, close: Boolean = true) = Path().apply {
        moveTo(p(xy[0], xy[1]).x, p(xy[0], xy[1]).y)
        var i = 2
        while (i < xy.size) { val q = p(xy[i], xy[i + 1]); lineTo(q.x, q.y); i += 2 }
        if (close) close()
    }

    /** A smooth closed shape through the points (x, y pairs). */
    fun blob(vararg xy: Float) = Path().apply {
        val pts = (xy.indices step 2).map { p(xy[it], xy[it + 1]) }
        val n = pts.size
        val m0 = (pts[n - 1] + pts[0]) / 2f
        moveTo(m0.x, m0.y)
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % n]; val m = (a + b) / 2f
            quadraticTo(a.x, a.y, m.x, m.y)
        }
        close()
    }

    fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = Path().apply {
        val o = p(cx, cy); addOval(Rect(o.x - len(rx), o.y - len(ry), o.x + len(rx), o.y + len(ry)))
    }

    fun solid(path: Path, color: Color, over: Boolean = false, alpha: Float = 1f) =
        s.drawPath(path, color, alpha = alpha, blendMode = if (over) BlendMode.Multiply else BlendMode.SrcOver)

    fun dots(path: Path, color: Color, fill: Float = 0.7f, angle: Float = 15f, scale: Float = 1f) =
        s.drawPath(path, Riso.dots(color, cell * scale, fill, angle), blendMode = BlendMode.Multiply)

    fun second(block: () -> Unit) {
        s.drawContext.transform.translate(mis.x, mis.y); block(); s.drawContext.transform.translate(-mis.x, -mis.y)
    }

    fun line(a: Offset, b: Offset, color: Color, w: Float) = s.drawLine(color, a, b, len(w), cap = StrokeCap.Round)

    fun photo(i: Int): ImageBitmap? = photos.getOrNull(i) ?: photos.firstOrNull()

    /** A print as an object: white border, the photo centre-cropped, a dot-screen shadow. Turned [deg] round [at]. */
    fun print(i: Int, at: Offset, w: Float, h: Float, deg: Float, pivot: Offset = at) {
        val pw = len(w); val ph = len(h)
        s.rotate(deg, pivot) {
            val tl = Offset(at.x - pw / 2, at.y - ph / 2)
            drawRect(Riso.dots(Ink.Black.color, cell * 0.7f, 0.6f, 45f), tl + Offset(len(0.03f), len(0.035f)), Size(pw, ph), blendMode = BlendMode.Multiply)
            drawRect(Palette.PrintWhite, tl, Size(pw, ph))
            val m = min(pw, ph) * 0.07f
            imageInto(photo(i), Rect(tl.x + m, tl.y + m, tl.x + pw - m, tl.y + ph - m))
        }
    }

    /** [img] centre-cropped to fill [r]; ink dots when there's no photo yet. */
    fun imageInto(img: ImageBitmap?, r: Rect) {
        if (r.width < 1 || r.height < 1) return
        if (img == null) {
            s.drawRect(A, r.topLeft, r.size)
            s.drawRect(Riso.dots(B, cell, 0.7f, 30f), r.topLeft, r.size, blendMode = BlendMode.Multiply)
            return
        }
        val k = max(r.width / img.width, r.height / img.height)
        val sw = (r.width / k).roundToInt().coerceIn(1, img.width); val sh = (r.height / k).roundToInt().coerceIn(1, img.height)
        s.drawImage(
            img, srcOffset = IntOffset((img.width - sw) / 2, (img.height - sh) / 2), srcSize = IntSize(sw, sh),
            dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()), dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
        )
    }

    /** A photo clipped to [shape] (a kite, a sail): still the true photo, only cut to shape. */
    fun photoIn(shape: Path, i: Int) {
        val r = shape.getBounds()
        s.clipPath(shape) { this@IslandPainter.imageInto(photo(i), r) }
    }

    /** The floating ground most islands stand on: a grassy top and a rocky underside tapering away. */
    fun ground(w: Float = 0.95f, y: Float = 0.32f, depth: Float = 0.55f) {
        val under = blob(-w, y, -w * 0.6f, y + depth * 0.45f, -w * 0.25f, y + depth * 0.75f, 0.02f, y + depth, w * 0.3f, y + depth * 0.62f, w * 0.7f, y + depth * 0.35f, w, y)
        solid(under, A)
        dots(under, Ink.Black.color, 0.45f, 45f)
        val top = oval(0f, y, w, 0.2f)
        second { solid(top, B, over = true) }
        dots(top, A, 0.5f, 75f, 0.8f)
    }

    /** Its shadow, far below: an ellipse of dots that shrinks as the island rises. */
    fun shadow(y: Float = 1.15f, w: Float = 0.7f) {
        val k = 1f - bob / u * 1.5f
        val o = Offset(c.x, c.y + y * u)
        val path = Path().apply { addOval(Rect(o.x - len(w) * k, o.y - len(0.07f) * k, o.x + len(w) * k, o.y + len(0.07f) * k)) }
        s.drawPath(path, Riso.dots(Ink.Black.color, cell * 0.8f, 0.5f, 45f), alpha = 0.55f, blendMode = BlendMode.Multiply)
    }

    fun swing(period: Float, amp: Float) = sin(t * 2 * PI.toFloat() / period + ph) * amp

    fun draw() {
        when (look.kind) {
            IslandKind.Book -> book()
            IslandKind.Mountain -> mountain()
            IslandKind.Branch -> branch()
            IslandKind.Balloon -> balloon()
            IslandKind.Kite -> kite()
            IslandKind.Boat -> boat()
            IslandKind.Cloud -> cloud()
            IslandKind.Moon -> moon()
            IslandKind.Tree -> tree()
            IslandKind.Lighthouse -> lighthouse()
            IslandKind.Tent -> tent()
            IslandKind.Desert -> desert()
            IslandKind.Pond -> pond()
        }
        extras()
    }

    fun book() {
        shadow()
        ground()
        // The album standing on its island, leaning a little: page block behind, cover in front.
        val deg = -6f + swing(9f, 0.6f)
        val at = p(0.02f, -0.18f)
        s.rotate(deg, p(0f, 0.3f)) {
            val w = len(0.78f); val h = len(0.9f)
            val tl = Offset(at.x - w / 2, at.y - h / 2)
            drawRect(Palette.PrintWhite, tl + Offset(len(0.07f), len(0.05f)), Size(w, h))
            for (k in 1..5) drawLine(Palette.SurfaceHigh, tl + Offset(w + len(0.07f) - k * len(0.012f), len(0.08f)), tl + Offset(w + len(0.07f) - k * len(0.012f), h + len(0.02f)), 1.5f)
            drawRect(A, tl, Size(w, h))
            drawRect(Riso.dots(Ink.Black.color, cell, 0.5f, 45f), tl, Size(len(0.09f), h), blendMode = BlendMode.Multiply)
            val m = len(0.12f)
            drawRect(Palette.PrintWhite, Offset(tl.x + m + len(0.04f), tl.y + m), Size(w - 2 * m, h * 0.52f))
            this@IslandPainter.imageInto(photo(0), Rect(tl.x + m + len(0.07f), tl.y + m + len(0.03f), tl.x + w - m + len(0.01f), tl.y + m + h * 0.52f - len(0.03f)))
            drawRect(B, Offset(tl.x + len(0.16f), tl.y + h * 0.74f), Size(w - len(0.3f), len(0.06f)), blendMode = BlendMode.Multiply)
        }
    }

    fun mountain() {
        shadow(1.12f, 0.8f)
        val under = blob(-1f, 0.38f, -0.5f, 0.62f, 0f, 0.95f, 0.45f, 0.66f, 1f, 0.38f)
        solid(under, A); dots(under, Ink.Black.color, 0.45f, 45f)
        val back = poly(-0.2f, 0.4f, 0.42f, -0.78f, 1.02f, 0.4f)
        second { solid(back, B, over = true) }
        val front = poly(-1f, 0.42f, -0.28f, -0.98f, 0.5f, 0.42f)
        solid(front, A, over = true)
        dots(front, Ink.Black.color, 0.35f, 60f)
        solid(poly(-0.28f, -0.98f, -0.47f, -0.62f, -0.36f, -0.67f, -0.27f, -0.57f, -0.17f, -0.7f, -0.1f, -0.63f), Palette.PrintWhite)
        solid(poly(0.42f, -0.78f, 0.28f, -0.52f, 0.37f, -0.56f, 0.45f, -0.48f, 0.53f, -0.58f), Palette.PrintWhite)
        // Prayer flags between the peaks: the album's own prints, on a sagging line.
        val a = p(-0.26f, -0.92f); val b = p(0.42f, -0.74f)
        val sag = len(0.32f)
        fun on(k: Float) = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k + sag * 4 * k * (1 - k))
        val string = Path().apply { moveTo(a.x, a.y); for (i in 1..20) { val q = on(i / 20f); lineTo(q.x, q.y) } }
        s.drawPath(string, Ink.Black.color, style = Stroke(len(0.012f)))
        for (i in 0 until 3) {
            val k = 0.22f + i * 0.28f
            val top = on(k)
            print(i, Offset(top.x, top.y + len(0.13f)), 0.2f, 0.24f, swing(3.5f + i * 0.4f, 7f), top)
        }
    }

    fun branch() {
        shadow(1.1f, 0.6f)
        // The branch, drifting a little.
        val tilt = swing(8f, 2f)
        s.rotate(tilt, p(0f, -0.3f)) {
            val bough = Path().apply {
                val a = p(-1.05f, -0.42f); moveTo(a.x, a.y)
                val c1 = p(-0.4f, -0.68f); val e = p(0.25f, -0.4f); quadraticTo(c1.x, c1.y, e.x, e.y)
                val c2 = p(0.6f, -0.25f); val f = p(1.02f, -0.52f); quadraticTo(c2.x, c2.y, f.x, f.y)
            }
            drawPath(bough, A, style = Stroke(len(0.07f), cap = StrokeCap.Round))
            drawPath(bough, Riso.dots(Ink.Black.color, cell, 0.5f, 45f), style = Stroke(len(0.07f), cap = StrokeCap.Round), blendMode = BlendMode.Multiply)
            line(p(-0.55f, -0.56f), p(-0.72f, -0.86f), A, 0.035f)
            line(p(0.55f, -0.33f), p(0.72f, -0.72f), A, 0.035f)
            // Leaves in the second ink.
            val leaves = listOf(-0.72f to -0.9f, -0.82f to -0.78f, 0.72f to -0.76f, 0.83f to -0.66f, -0.15f to -0.66f, 0.95f to -0.56f, -1f to -0.5f)
            for ((i, xy) in leaves.withIndex()) {
                val (x, y) = xy
                val leaf = Path().apply { val o = p(x, y); addOval(Rect(o.x - len(0.1f), o.y - len(0.045f), o.x + len(0.1f), o.y + len(0.045f))) }
                rotate(-30f + i * 37f, p(x, y)) { this@IslandPainter.second { this@IslandPainter.solid(leaf, B, over = true) } }
            }
            // Prints hanging on threads, each swinging to its own time.
            val hooks = listOf(-0.62f to -0.58f, 0.05f to -0.47f, 0.62f to -0.32f)
            for ((i, hk) in hooks.withIndex()) {
                val top = p(hk.first, hk.second)
                val deg = swing(2.6f + i * 0.5f, 9f)
                val drop = len(0.28f + i * 0.08f)
                rotate(deg, top) {
                    drawLine(Ink.Black.color, top, top + Offset(0f, drop), len(0.01f))
                    drawCircle(Palette.Accent, len(0.025f), top + Offset(0f, drop))
                }
                this@IslandPainter.print(i, top + Offset(0f, drop + len(0.2f)), 0.34f, 0.38f, deg, top)
            }
        }
    }

    fun balloon() {
        shadow(1.2f, 0.45f)
        val sway = swing(7f, 4f)
        s.rotate(sway, p(0f, -0.5f)) {
            val env = blob(0f, -1.05f, 0.5f, -0.95f, 0.62f, -0.5f, 0.38f, -0.05f, 0.1f, 0.16f, -0.1f, 0.16f, -0.38f, -0.05f, -0.62f, -0.5f, -0.5f, -0.95f)
            this@IslandPainter.solid(env, A)
            // Gores in the second ink, every other one.
            clipPath(env) {
                for (k in -3..3 step 2) {
                    val g = this@IslandPainter.poly(k * 0.18f - 0.09f, -1.2f, k * 0.18f + 0.09f, -1.2f, k * 0.05f + 0.03f, 0.2f, k * 0.05f - 0.03f, 0.2f)
                    this@IslandPainter.second { this@IslandPainter.solid(g, B, over = true) }
                }
                this@IslandPainter.dots(this@IslandPainter.oval(0.28f, -0.4f, 0.4f, 0.6f), Ink.Black.color, 0.35f, 45f)
            }
            for (x in listOf(-0.1f, 0.1f)) this@IslandPainter.line(p(x, 0.16f), p(x * 1.5f, 0.42f), Ink.Black.color, 0.012f)
            val basket = this@IslandPainter.poly(-0.16f, 0.42f, 0.16f, 0.42f, 0.13f, 0.6f, -0.13f, 0.6f)
            this@IslandPainter.solid(basket, Ink.Burgundy.color)
            this@IslandPainter.dots(basket, Ink.Black.color, 0.5f, 0f)
            // The cover print trailing below on a line.
            val hook = p(0f, 0.6f)
            val deg = swing(3f, 6f)
            drawLine(Ink.Black.color, hook, hook + Offset(0f, len(0.16f)), len(0.01f))
            this@IslandPainter.print(0, hook + Offset(0f, len(0.36f)), 0.42f, 0.4f, deg, hook)
        }
    }

    fun kite() {
        shadow(1.2f, 0.4f)
        val sway = swing(5f, 6f)
        val ctr = p(0.1f, -0.42f)
        s.rotate(sway, ctr) {
            val top = p(0.1f, -1.05f); val right = p(0.62f, -0.48f); val bottom = p(0.1f, 0.32f); val left = p(-0.42f, -0.48f)
            val diamond = Path().apply { moveTo(top.x, top.y); lineTo(right.x, right.y); lineTo(bottom.x, bottom.y); lineTo(left.x, left.y); close() }
            val inner = Path().apply {
                fun q(o: Offset) = Offset(ctr.x + (o.x - ctr.x) * 0.84f, ctr.y + (o.y - ctr.y) * 0.84f)
                val a = q(top); val b = q(right); val c = q(bottom); val d = q(left)
                moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
            }
            this@IslandPainter.photoIn(inner, 0)
            // The frame round the photo: first ink, its upper half overprinted with the second.
            val frame = Path().apply { op(diamond, inner, PathOperation.Difference) }
            drawPath(frame, A)
            val upper = Path().apply { moveTo(left.x, left.y); lineTo(top.x, top.y); lineTo(right.x, right.y); close() }
            this@IslandPainter.second { clipPath(frame) { drawPath(upper, B, blendMode = BlendMode.Multiply) } }
            drawLine(Ink.Black.color, top, bottom, len(0.012f)); drawLine(Ink.Black.color, left, right, len(0.012f))
            // The tail, waving, with bows.
            val tail = Path().apply {
                moveTo(bottom.x, bottom.y)
                for (i in 1..24) { val k = i / 24f; lineTo(bottom.x + len(sin(k * 7f + t * 3f + ph) * 0.12f * k) - len(k * 0.35f), bottom.y + len(k * 0.85f)) }
            }
            drawPath(tail, Ink.Black.color, style = Stroke(len(0.012f)))
            for (i in 1..3) {
                val k = i / 4f
                val o = Offset(bottom.x + len(sin(k * 7f + t * 3f + ph) * 0.12f * k) - len(k * 0.35f), bottom.y + len(k * 0.85f))
                val bow = Path().apply {
                    moveTo(o.x, o.y); lineTo(o.x - len(0.07f), o.y - len(0.045f)); lineTo(o.x - len(0.07f), o.y + len(0.045f)); close()
                    moveTo(o.x, o.y); lineTo(o.x + len(0.07f), o.y - len(0.045f)); lineTo(o.x + len(0.07f), o.y + len(0.045f)); close()
                }
                drawPath(bow, if (i % 2 == 0) A else B, blendMode = BlendMode.Multiply)
            }
        }
        // The string, off to a small rock below.
        val anchor = p(-0.75f, 0.62f)
        s.drawLine(Ink.Black.color, p(0.1f, -0.42f), anchor, len(0.008f))
        val rock = blob(-0.92f, 0.62f, -0.75f, 0.56f, -0.58f, 0.63f, -0.68f, 0.8f, -0.82f, 0.78f)
        solid(rock, A); dots(rock, Ink.Black.color, 0.5f, 45f)
    }

    fun boat() {
        shadow(1.1f, 0.75f)
        // Water: lines of the second ink in an oval.
        val water = oval(0f, 0.42f, 1f, 0.24f)
        second { s.drawPath(water, Riso.lines(B, cell * 0.9f, 0.45f, 0f), blendMode = BlendMode.Multiply) }
        dots(oval(0f, 0.46f, 0.85f, 0.15f), A, 0.4f, 30f)
        val rock = swing(4.5f, 5f)
        s.rotate(rock, p(0f, 0.3f)) {
            val hull = this@IslandPainter.poly(-0.66f, 0.12f, 0.66f, 0.12f, 0.42f, 0.42f, -0.42f, 0.42f)
            this@IslandPainter.solid(hull, A)
            this@IslandPainter.dots(this@IslandPainter.poly(-0.66f, 0.12f, 0f, 0.12f, -0.1f, 0.42f, -0.42f, 0.42f), Ink.Black.color, 0.45f, 45f)
            this@IslandPainter.line(p(0f, 0.14f), p(0f, -1.0f), Ink.Black.color, 0.025f)
            // The sail is the cover photo, cut to a sail.
            val sail = this@IslandPainter.poly(0.04f, -0.98f, 0.04f, 0.04f, 0.68f, 0.04f)
            drawPath(this@IslandPainter.poly(0.02f, -1.02f, 0.02f, 0.07f, 0.74f, 0.07f), Palette.PrintWhite)
            this@IslandPainter.photoIn(sail, 0)
            val jib = this@IslandPainter.poly(-0.04f, -0.8f, -0.04f, 0.04f, -0.5f, 0.04f)
            this@IslandPainter.second { this@IslandPainter.solid(jib, B, over = true) }
            val flag = this@IslandPainter.poly(0f, -1f, 0f, -0.88f, 0.2f + 0.03f * sin(t * 6 + ph), -0.94f)
            this@IslandPainter.solid(flag, Palette.Accent)
        }
    }

    fun cloud() {
        shadow(1.15f, 0.55f)
        val drift = swing(10f, 0.04f)
        val puffs = listOf(Triple(-0.5f, -0.35f, 0.32f), Triple(-0.1f, -0.58f, 0.42f), Triple(0.38f, -0.42f, 0.34f), Triple(0.72f, -0.25f, 0.24f), Triple(-0.8f, -0.18f, 0.22f))
        val cloud = Path()
        for ((x, y, r) in puffs) cloud.op(cloud, oval(x + drift, y, r, r * 0.92f), PathOperation.Union)
        cloud.op(cloud, oval(0f + drift, -0.12f, 0.95f, 0.2f), PathOperation.Union)
        second { solid(cloud, B, over = true) }
        dots(cloud, A, 0.5f, 30f, 0.9f)
        // A washing line under it, prints pegged on.
        val a = p(-0.7f + drift, -0.02f); val b = p(0.7f + drift, -0.02f)
        val sag = len(0.18f)
        fun on(k: Float) = Offset(a.x + (b.x - a.x) * k, a.y + sag * 4 * k * (1 - k))
        s.drawPath(Path().apply { moveTo(a.x, a.y); for (i in 1..20) { val q = on(i / 20f); lineTo(q.x, q.y) } }, Ink.Black.color, style = Stroke(len(0.01f)))
        for (i in 0 until 3) {
            val top = on(0.2f + i * 0.3f)
            val deg = swing(3f + i * 0.6f, 6f)
            print(i, Offset(top.x, top.y + len(0.2f)), 0.3f, 0.34f, deg, top)
            s.drawRect(Palette.Accent, top - Offset(len(0.012f), len(0.04f)), Size(len(0.024f), len(0.08f)))
        }
        // A little rain in the first ink.
        for (i in 0 until 6) {
            val x = -0.6f + i * 0.24f + drift
            val y = 0.62f + ((t * 0.5f + i * 0.37f) % 1f) * 0.4f
            line(p(x, y), p(x - 0.03f, y + 0.08f), A, 0.02f)
        }
    }

    fun moon() {
        shadow(1.15f, 0.4f)
        val disc = oval(0f, -0.38f, 0.66f, 0.66f)
        val bite = oval(0.32f, -0.52f, 0.56f, 0.56f)
        val crescent = Path().apply { op(disc, bite, PathOperation.Difference) }
        second { solid(crescent, B, over = true) }
        dots(crescent, A, 0.55f, 45f)
        // Stars that twinkle.
        val stars = listOf(0.7f to -0.95f, 0.95f to -0.4f, 0.45f to 0.15f, -0.85f to -0.95f, 0.15f to -1.1f)
        for ((i, st) in stars.withIndex()) {
            val k = 0.5f + 0.5f * sin(t * 2.2f + i * 1.3f + ph)
            val o = p(st.first, st.second); val r = len(0.05f + 0.03f * k)
            s.drawLine(Ink.Yellow.color, o - Offset(r, 0f), o + Offset(r, 0f), len(0.025f), cap = StrokeCap.Round, blendMode = BlendMode.Multiply)
            s.drawLine(Ink.Yellow.color, o - Offset(0f, r), o + Offset(0f, r), len(0.025f), cap = StrokeCap.Round, blendMode = BlendMode.Multiply)
        }
        // A print hanging from the moon's lower tip, turning slowly.
        val tip = p(-0.02f, 0.22f)
        val deg = swing(5f, 10f)
        s.drawLine(Ink.Black.color, tip, tip + Offset(0f, len(0.2f)), len(0.01f))
        print(0, tip + Offset(0f, len(0.45f)), 0.44f, 0.46f, deg, tip)
    }

    fun tree() {
        shadow()
        ground(0.85f, 0.38f, 0.5f)
        val trunk = poly(-0.08f, 0.4f, 0.08f, 0.4f, 0.05f, -0.3f, -0.05f, -0.3f)
        solid(trunk, Ink.Black.color)
        line(p(0f, -0.15f), p(0.42f, -0.4f), Ink.Black.color, 0.04f)
        val sway = swing(6f, 0.025f)
        val canopy = Path()
        for ((x, y, r) in listOf(Triple(-0.3f, -0.58f, 0.36f), Triple(0.12f, -0.8f, 0.42f), Triple(0.5f, -0.52f, 0.34f), Triple(0.08f, -0.42f, 0.36f))) canopy.op(canopy, oval(x + sway, y, r, r), PathOperation.Union)
        solid(canopy, A, over = true)
        second { dots(canopy, B, 0.75f, 30f, 1.1f) }
        // A swing from the low branch, a print on its seat.
        val l = p(0.28f, -0.32f); val r = p(0.5f, -0.44f)
        val deg = swing(3.2f, 12f)
        s.rotate(deg, (l + r) / 2f) {
            drawLine(Ink.Black.color, l, l + Offset(0f, len(0.42f)), len(0.01f))
            drawLine(Ink.Black.color, r, r + Offset(0f, len(0.54f)), len(0.01f))
        }
        print(0, (l + r) / 2f + Offset(0f, len(0.66f)), 0.34f, 0.3f, deg - 8f, (l + r) / 2f)
    }

    fun lighthouse() {
        shadow()
        ground(0.9f, 0.4f, 0.5f)
        // The beam sweeps.
        val k = sin(t * 0.9f + ph)
        val lamp = p(-0.25f, -0.78f)
        val beam = Path().apply {
            moveTo(lamp.x, lamp.y); lineTo(lamp.x + len(1.2f) * k, lamp.y - len(0.3f)); lineTo(lamp.x + len(1.2f) * k, lamp.y + len(0.12f)); close()
        }
        s.drawPath(beam, Riso.dots(Ink.Yellow.color, cell, 0.8f, 15f), alpha = 0.4f + 0.6f * abs(k), blendMode = BlendMode.Multiply)
        val tower = poly(-0.4f, 0.42f, -0.1f, 0.42f, -0.17f, -0.68f, -0.33f, -0.68f)
        solid(tower, Palette.PrintWhite)
        s.clipPath(tower) { for (y in listOf(0.2f, -0.2f, -0.55f)) this@IslandPainter.solid(this@IslandPainter.poly(-0.5f, y, 0f, y - 0.06f, 0f, y + 0.1f, -0.5f, y + 0.16f), A) }
        val room = poly(-0.36f, -0.68f, -0.14f, -0.68f, -0.14f, -0.86f, -0.36f, -0.86f)
        second { solid(room, B, over = true) }
        solid(poly(-0.4f, -0.86f, -0.1f, -0.86f, -0.25f, -0.98f), Ink.Black.color)
        // The cover print propped against a rock beside it.
        print(0, p(0.42f, 0.12f), 0.5f, 0.44f, 8f)
    }

    fun tent() {
        shadow()
        ground(0.95f, 0.36f, 0.5f)
        val tent = poly(-0.72f, 0.38f, -0.25f, -0.4f, 0.2f, 0.38f)
        solid(tent, A)
        second { solid(poly(-0.25f, -0.4f, 0.2f, 0.38f, 0.3f, 0.3f, -0.12f, -0.42f), B, over = true) }
        solid(poly(-0.36f, 0.38f, -0.25f, 0.02f, -0.14f, 0.38f), Ink.Black.color)
        // A campfire, flickering in pencil red.
        val f = 0.85f + 0.15f * sin(t * 9f + ph) * sin(t * 5.3f)
        val flame = blob(0.4f, 0.3f, 0.48f, 0.3f - 0.22f * f, 0.53f, 0.12f, 0.6f, 0.3f - 0.3f * f, 0.66f, 0.3f)
        solid(flame, Palette.Accent)
        second { solid(blob(0.47f, 0.32f, 0.53f, 0.32f - 0.14f * f, 0.59f, 0.32f), Ink.Yellow.color, over = true) }
        line(p(0.38f, 0.34f), p(0.68f, 0.3f), Ink.Black.color, 0.03f)
        // A flag of the cover print on a pole.
        line(p(0.72f, 0.36f), p(0.72f, -0.62f), Ink.Black.color, 0.02f)
        print(0, p(0.92f, -0.42f), 0.38f, 0.34f, swing(2.5f, 4f), p(0.72f, -0.55f))
    }

    fun desert() {
        shadow()
        // A sun behind it all.
        second { solid(oval(0.45f, -0.6f, 0.36f, 0.36f), B, over = true) }
        val dune = blob(-1f, 0.38f, -0.5f, 0.1f, 0f, 0.26f, 0.6f, 0.05f, 1f, 0.38f, 0.4f, 0.75f, -0.4f, 0.72f)
        dots(dune, Ink.Sunflower.color, 0.85f, 15f)
        dots(dune, A, 0.35f, 60f)
        // A cactus.
        val trunk = Path().apply { val a = p(-0.48f, 0.3f); val b = p(-0.32f, -0.62f); addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(a.x, b.y, b.x, a.y), len(0.08f), len(0.08f))) }
        solid(trunk, A, over = true)
        line(p(-0.47f, -0.12f), p(-0.66f, -0.12f), A, 0.1f); line(p(-0.66f, -0.12f), p(-0.66f, -0.38f), A, 0.1f)
        line(p(-0.33f, -0.28f), p(-0.18f, -0.28f), A, 0.1f); line(p(-0.18f, -0.28f), p(-0.18f, -0.46f), A, 0.1f)
        // A postcard on a stick: the cover print.
        line(p(0.38f, 0.32f), p(0.38f, -0.12f), Ink.Black.color, 0.02f)
        print(0, p(0.38f, -0.32f), 0.5f, 0.38f, -5f + swing(4f, 2f))
    }

    fun pond() {
        shadow(1.05f, 0.85f)
        val water = oval(0f, 0.25f, 1f, 0.42f)
        second { s.drawPath(water, Riso.lines(B, cell, 0.5f, -8f), blendMode = BlendMode.Multiply) }
        val rim = Path().apply { op(oval(0f, 0.25f, 1.05f, 0.47f), water, PathOperation.Difference) }
        solid(rim, A)
        dots(oval(0f, 0.6f, 0.75f, 0.12f), A, 0.5f, 45f)
        // Reeds at the back, swaying.
        for (i in 0 until 5) {
            val x = -0.85f + i * 0.09f
            val lean = swing(3f + i * 0.4f, 0.05f)
            line(p(x, 0.05f), p(x + lean + 0.03f, -0.42f - (i % 3) * 0.12f), A, 0.025f)
            solid(oval(x + lean + 0.03f, -0.42f - (i % 3) * 0.12f, 0.025f, 0.08f), Ink.Burgundy.color, over = true)
        }
        // Lily pads, each with its notch, drifting.
        val pads = listOf(Triple(0.42f, 0.2f, 0.36f), Triple(-0.3f, 0.42f, 0.24f), Triple(0.05f, 0.04f, 0.17f))
        for ((i, pad) in pads.withIndex()) {
            val (x, y, r) = pad
            val dx = swing(7f + i, 0.03f)
            val notch = poly(x + dx, y, x + dx + r * 1.1f, y - r * 0.35f, x + dx + r * 1.1f, y + r * 0.05f)
            val leaf = Path().apply { op(oval(x + dx, y, r, r * 0.48f), notch, PathOperation.Difference) }
            solid(leaf, Ink.Green.color.takeIf { look.a != Ink.Green } ?: A, over = true)
            dots(leaf, A, 0.4f, 30f, 0.8f)
        }
        val fx = -0.3f + swing(8f, 0.03f)
        for (k in 0 until 5) s.rotate(k * 72f + swing(6f, 6f), p(fx, 0.36f)) { this@IslandPainter.solid(this@IslandPainter.oval(fx, 0.3f, 0.035f, 0.07f), Ink.Pink.color, over = true) }
        solid(oval(fx, 0.36f, 0.03f, 0.03f), Ink.Yellow.color, over = true)
        // The cover print floats on the big pad, turning gently.
        print(0, p(0.42f + swing(7f, 0.03f), -0.05f), 0.46f, 0.4f, -8f + swing(9f, 4f))
    }

    fun extras() {
        when (look.extra) {
            Extra.None -> {}
            Extra.Birds -> for (i in 0 until 3) {
                val x = -1.1f + ((t * 0.12f + i * 0.31f + ph * 0.1f) % 1f) * 2.4f
                val y = -1.05f + i * 0.14f + sin(t * 1.3f + i) * 0.04f
                val flap = 0.05f + 0.035f * sin(t * 8f + i * 2f)
                val o = p(x, y)
                val w = Path().apply { moveTo(o.x - len(0.08f), o.y - len(flap)); quadraticTo(o.x - len(0.03f), o.y - len(0.01f), o.x, o.y); quadraticTo(o.x + len(0.03f), o.y - len(0.01f), o.x + len(0.08f), o.y - len(flap)) }
                s.drawPath(w, Ink.Black.color, style = Stroke(len(0.018f), cap = StrokeCap.Round))
            }
            Extra.Stars -> for (i in 0 until 7) {
                val x = -1.05f + (i * 0.37f) % 2.1f; val y = -1.15f + (i * 0.53f) % 0.6f
                val k = 0.5f + 0.5f * sin(t * 2.5f + i * 1.7f)
                s.drawCircle(Ink.Yellow.color, len(0.02f + 0.025f * k), p(x, y), blendMode = BlendMode.Multiply)
            }
            Extra.Confetti -> for (i in 0 until 14) {
                val x = -1f + (i * 0.29f) % 2f + sin(t + i) * 0.04f
                val y = -1.2f + ((t * 0.18f + i * 0.17f) % 1f) * 2.3f
                s.rotate(t * 90f + i * 40f, p(x, y)) {
                    drawRect(if (i % 2 == 0) A else B, p(x, y) - Offset(len(0.025f), len(0.012f)), Size(len(0.05f), len(0.024f)), blendMode = BlendMode.Multiply)
                }
            }
            Extra.Fireflies -> for (i in 0 until 8) {
                val x = -0.9f + (i * 0.43f) % 1.8f + sin(t * 0.7f + i) * 0.1f
                val y = -0.9f + (i * 0.31f) % 1.2f + cos(t * 0.5f + i * 2) * 0.08f
                val k = (0.5f + 0.5f * sin(t * 3f + i * 2.1f)).let { it * it }
                s.drawCircle(Ink.Yellow.color, len(0.05f), p(x, y), alpha = k * 0.6f, blendMode = BlendMode.Multiply)
                s.drawCircle(Palette.Accent, len(0.015f), p(x, y), alpha = k)
            }
        }
    }
}
