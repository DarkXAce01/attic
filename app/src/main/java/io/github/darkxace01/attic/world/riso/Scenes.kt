package io.github.darkxace01.attic.world.riso

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.ui.theme.Style
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The islands as little scenes, the album's photo inside each story: the window of a cable car, the
 * sail of a paper boat, a billboard in the desert. Each scene is drawn once, in a 300 × 300 space, and
 * printed the current theme's way:
 *
 * - Riso: flat spot inks overprinted (multiply), tone as a dot screen. The album's two chosen inks print
 *   each scene's two main parts.
 * - Infrared: crimson leaves and cyan water, every shape with a halation glow.
 * - Night broadcast: glowing vector lines on the glass.
 * - Cyanotype: white (or blue) lines where the sun got through, hatching for shade.
 * - Kodachrome: warm saturated paint with a soft shadow.
 *
 * The photo is always drawn whole and untouched; a theme may only cut it to a shape.
 */
internal class Scene(
    private val cv: Canvas,
    private val look: IslandLook,
    private val photo: Bitmap?,
    /** Pixels per scene unit. */
    private val k: Float,
    private val t: Float,
    seed: Int,
) {
    enum class R { Leaf, Leaf2, Water, Wood, Stone, Stone2, Red, Pink, Yellow, Orange, Sand, Sky, Paper, Snow, Dark, Light }

    private enum class Mode { Riso, Ir, Crt, Cy, Kc }

    private val mode = when (Palette.style) {
        Style.Riso -> Mode.Riso; Style.Infrared -> Mode.Ir; Style.Broadcast -> Mode.Crt
        Style.Cyanotype -> Mode.Cy; Style.Kodachrome -> Mode.Kc
    }
    private val dark = Palette.dark
    private val lines = mode == Mode.Crt || mode == Mode.Cy
    private val bg = argb(Palette.Ground)
    /** The line colour of the line themes, and the ink of outlines elsewhere. */
    private val ink = if (mode == Mode.Crt) argb(Palette.Second) else argb(Palette.Ink)
    private val accent = argb(Palette.Accent)
    private val white = argb(Palette.PrintWhite)
    private val glow = argb(Palette.Second.copy(alpha = if (dark) 0.75f else 0.5f))
    private val ph = seed * 1.7f

    private fun argb(c: androidx.compose.ui.graphics.Color) = android.graphics.Color.argb(
        (c.alpha * 255).roundToInt(), (c.red * 255).roundToInt(), (c.green * 255).roundToInt(), (c.blue * 255).roundToInt(),
    )

    // ---------- Colours ----------

    private val palette: Map<R, Int> = when (mode) {
        Mode.Riso -> if (dark) RISO_DARK else RISO
        Mode.Ir -> IR
        Mode.Kc -> KC
        else -> emptyMap()
    }

    /** In Riso, the album's own two inks print the scene's two main parts. */
    private val heroes: Pair<R, R>? = if (mode != Mode.Riso) null else when (look.kind) {
        IslandKind.Book -> R.Red to R.Yellow
        IslandKind.Mountain -> R.Stone to R.Yellow
        IslandKind.Branch -> R.Leaf to R.Pink
        IslandKind.Balloon -> R.Red to R.Yellow
        IslandKind.Kite -> R.Red to R.Yellow
        IslandKind.Boat -> R.Water to R.Red
        IslandKind.Cloud -> R.Water to R.Yellow
        IslandKind.Moon -> R.Yellow to R.Pink
        IslandKind.Tree -> R.Leaf to R.Red
        IslandKind.Lighthouse -> R.Red to R.Water
        IslandKind.Tent -> R.Pink to R.Leaf2
        IslandKind.Desert -> R.Orange to R.Red
        IslandKind.Pond -> R.Water to R.Leaf2
    }

    private fun col(r: R): Int {
        if (lines) return if (r == R.Red || r == R.Yellow || r == R.Orange || r == R.Light || r == R.Pink) accent else ink
        heroes?.let { (a, b) -> if (r == a) return argb(look.a.color); if (r == b) return argb(look.b.color) }
        if (r == R.Dark) return argb(Palette.Ink)
        if (r == R.Paper) return white
        return palette[r] ?: ink
    }

    // ---------- Building paths, the way a 2D canvas does ----------

    private val p = Path()
    private val pm = Matrix()
    private val pt = FloatArray(2)
    private var started = false

    private fun map(x: Float, y: Float): FloatArray { pt[0] = x; pt[1] = y; pm.mapPoints(pt); return pt }
    private fun begin() { p.reset(); pm.reset(); started = false }
    private fun m(x: Float, y: Float) { val q = map(x, y); p.moveTo(q[0], q[1]); started = true }
    private fun l(x: Float, y: Float) { if (!started) return m(x, y); val q = map(x, y); p.lineTo(q[0], q[1]) }
    private fun q(cx: Float, cy: Float, x: Float, y: Float) {
        val a = map(cx, cy).copyOf(); val b = map(x, y); p.quadTo(a[0], a[1], b[0], b[1])
    }
    private fun b(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        val a = map(c1x, c1y).copyOf(); val c = map(c2x, c2y).copyOf(); val e = map(x, y); p.cubicTo(a[0], a[1], c[0], c[1], e[0], e[1])
    }
    private fun z() { p.close(); started = false }

    /** An elliptical arc from [a0] to [a1] (radians), joined to whatever came before with a line. */
    private fun ell(x: Float, y: Float, rx: Float, ry: Float, rot: Float, a0: Float, a1: Float, ccw: Boolean = false) {
        var sweep = a1 - a0
        val full = abs(sweep) >= 2 * PI
        if (full) sweep = (2 * PI).toFloat() * if (ccw) -1 else 1
        else if (!ccw) { while (sweep < 0) sweep += (2 * PI).toFloat() } else { while (sweep > 0) sweep -= (2 * PI).toFloat() }
        val n = max(8, ceil(abs(sweep) / (2 * PI) * 72).toInt())
        val cr = cos(rot); val sr = sin(rot)
        for (i in 0..n) {
            val a = a0 + sweep * i / n
            val ex = cos(a) * rx; val ey = sin(a) * ry
            l(x + ex * cr - ey * sr, y + ex * sr + ey * cr)
        }
        if (full) z()
    }
    private fun arc(x: Float, y: Float, r: Float, a0: Float, a1: Float, ccw: Boolean = false) = ell(x, y, r, r, 0f, a0, a1, ccw)
    private fun oval(x: Float, y: Float, rx: Float, ry: Float, rot: Float = 0f) { started = false; ell(x, y, rx, ry, rot, 0f, 7f) }
    private fun circles(vararg c: Float) { var i = 0; while (i < c.size) { started = false; arc(c[i], c[i + 1], c[i + 2], 0f, 7f); i += 3 } }
    private fun rect(x: Float, y: Float, w: Float, h: Float) { m(x, y); l(x + w, y); l(x + w, y + h); l(x, y + h); z() }
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) {
        if (r <= 0f) return rect(x, y, w, h)
        val hp = (PI / 2).toFloat()
        started = false
        arc(x + w - r, y + r, r, -hp, 0f); arc(x + w - r, y + h - r, r, 0f, hp)
        arc(x + r, y + h - r, r, hp, 2 * hp); arc(x + r, y + r, r, 2 * hp, 3 * hp); z()
    }
    private fun star(x: Float, y: Float, r: Float) {
        for (i in 0 until 10) { val a = -PI / 2 + i * PI / 5; val rad = if (i % 2 == 1) r * 0.45f else r; l(x + cos(a).toFloat() * rad, y + sin(a).toFloat() * rad) }
        z()
    }
    /** Points added inside [block] are turned [deg] about (x, y), as a canvas's save, translate and rotate do. */
    private inline fun turned(x: Float, y: Float, deg: Float, block: () -> Unit) {
        val keep = Matrix(pm); pm.preTranslate(x, y); pm.preRotate(deg); started = false; block(); pm.set(keep)
    }

    // ---------- Painting ----------

    private class O(
        val shade: Boolean = false, val line: Float = 0f, val glow: Float? = null, val flat: Boolean = false,
        val alpha: Float = 1f, val outline: Float = 0f, val over: Boolean = false, val open: Boolean = false, val lw: Float? = null,
    )

    private fun paint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private fun blur(r: Float) = r * 0.5f

    private fun multiply(pt: Paint) { pt.blendMode = android.graphics.BlendMode.MULTIPLY }

    private val patterns = HashMap<String, BitmapShader>()

    /** A screen of dots, [step] scene units apart, laid out in screen pixels so they stay crisp. */
    private fun dots(color: Int, step: Float): BitmapShader {
        val n = max(3, (step * k).roundToInt())
        return patterns.getOrPut("d$color/$n") {
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawCircle(n / 2f, n / 2f, n * 0.27f, paint(color))
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(Matrix().apply { setScale(1 / k, 1 / k) }) }
        }
    }

    private fun hatch(color: Int): BitmapShader {
        val n = max(4, (6 * k).roundToInt())
        return patterns.getOrPut("h$color/$n") {
            val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawLine(0f, n.toFloat(), n.toFloat(), 0f, paint(color).apply { strokeWidth = k })
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(Matrix().apply { setScale(1 / k, 1 / k) }) }
        }
    }

    private fun strokePaint(color: Int, w: Float) = paint(color).apply {
        style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    /** Fills the path [build] makes, the theme's way (see the class comment). */
    private fun fill(r: R, o: O = O(), build: () -> Unit) {
        begin(); build()
        val path = Path(p)
        val c = col(r)
        if (lines) {
            if (o.outline > 0f) {
                cv.drawPath(path, strokePaint(c, 3f).apply { if (mode == Mode.Crt) setShadowLayer(blur(7f), 0f, 0f, c) })
                cv.drawPath(path, paint(bg))
            } else {
                if (!o.open) cv.drawPath(path, paint(bg))
                cv.drawPath(path, strokePaint(c, o.lw ?: if (mode == Mode.Crt) 1.5f else 1.4f).apply { if (mode == Mode.Crt) setShadowLayer(blur(7f), 0f, 0f, c) })
                if (o.shade) cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = hatch(c); alpha = if (mode == Mode.Crt) 115 else 140 })
            }
            return
        }
        if (o.outline > 0f && mode == Mode.Riso) cv.drawPath(path, strokePaint(argb(Palette.Ink), o.outline * 2))
        val mul = mode == Mode.Riso && !dark && !o.over && o.outline == 0f
        val fp = paint(c).apply {
            alpha = (alpha * o.alpha).roundToInt()
            if (mul) multiply(this)
            if (mode == Mode.Ir && !o.flat) setShadowLayer(blur(o.glow ?: 10f), 0f, 0f, glow)
            if (mode == Mode.Kc && !o.flat) setShadowLayer(blur(5f), 0f, 1f, android.graphics.Color.argb(71, 60, 30, 10))
        }
        cv.drawPath(path, fp)
        if (o.shade && mode == Mode.Riso) cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = dots(if (dark) android.graphics.Color.argb(115, 0, 0, 0) else android.graphics.Color.argb(128, 29, 30, 34), 5f)
            if (mul) multiply(this)
        })
        if (o.shade && mode == Mode.Kc) cv.drawPath(path, paint(android.graphics.Color.argb(36, 0, 0, 0)))
        if (o.line > 0f) cv.drawPath(path, strokePaint(argb(Palette.Ink), o.line))
    }

    private fun stroke(r: R, w: Float = 2f, alpha: Float = 1f, dash: FloatArray? = null, glowing: Boolean = false, build: () -> Unit) {
        begin(); build()
        val c = col(r)
        val sp = strokePaint(c, w).apply {
            this.alpha = (this.alpha * alpha).roundToInt()
            if (mode == Mode.Crt) setShadowLayer(blur(6f), 0f, 0f, c)
            if (mode == Mode.Ir && glowing) setShadowLayer(blur(8f), 0f, 0f, glow)
            if (dash != null) pathEffect = DashPathEffect(dash, 0f)
        }
        cv.drawPath(p, sp)
    }

    private fun floorShadow(x: Float, y: Float, rx: Float, ry: Float) {
        val e = RectF(x - rx, y - ry, x + rx, y + ry)
        when (mode) {
            Mode.Riso -> cv.drawOval(e, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = dots(if (dark) android.graphics.Color.argb(89, 238, 234, 224) else android.graphics.Color.argb(115, 29, 30, 34), 5f)
            })
            Mode.Ir -> cv.drawOval(e, paint(col(R.Water)).apply { alpha = 71; setShadowLayer(blur(16f), 0f, 0f, col(R.Water)) })
            Mode.Kc -> cv.drawOval(e, paint(android.graphics.Color.argb(if (dark) 90 else 41, 60, 30, 10)).apply { if (dark) color = android.graphics.Color.argb(110, 0, 0, 0) })
            else -> cv.drawOval(e, strokePaint(ink, 1f).apply { alpha = 128; pathEffect = DashPathEffect(floatArrayOf(3f, 5f), 0f) })
        }
    }

    /** The photo centre-cropped to fill the box; a plain tone while there's no photo yet. */
    private fun cover(x: Float, y: Float, w: Float, h: Float) {
        val img = photo
        if (img == null) { cv.drawRect(x, y, x + w, y + h, paint(if (lines) bg else col(R.Stone2))); return }
        val s = max(w / img.width, h / img.height)
        val dw = img.width * s; val dh = img.height * s
        cv.drawBitmap(img, null, RectF(x + (w - dw) / 2, y + (h - dh) / 2, x + (w + dw) / 2, y + (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    /** The photo inside a shape (a sail, a window, a kite): drawn as it is, then the shape's edge. */
    private fun photoIn(x: Float, y: Float, w: Float, h: Float, line: Boolean = true, build: () -> Unit) {
        begin(); build()
        val path = Path(p)
        cv.save(); cv.clipPath(path); cover(x, y, w, h); cv.restore()
        when (mode) {
            Mode.Crt -> cv.drawPath(path, strokePaint(ink, 1.4f).apply { setShadowLayer(blur(10f), 0f, 0f, ink) })
            Mode.Cy -> cv.drawPath(path, strokePaint(ink, 1.4f))
            else -> if (line) cv.drawPath(path, strokePaint(if (mode == Mode.Riso) argb(Palette.Ink) else android.graphics.Color.argb(89, 0, 0, 0), if (mode == Mode.Riso) 1.6f else 1f))
        }
    }

    /** A print: a white border, its shadow in the theme's manner, the photo whole. */
    private fun print(cx: Float, cy: Float, w: Float, h: Float, rot: Float, border: Float? = null) {
        cv.save(); cv.translate(cx, cy); cv.rotate(rot)
        val bd = border ?: max(4f, w * 0.06f)
        if (mode == Mode.Riso) cv.drawRect(-w / 2 + 4, -h / 2 + 4, w / 2 + 4, h / 2 + 4, Paint().apply {
            shader = dots(if (dark) android.graphics.Color.argb(115, 238, 234, 224) else android.graphics.Color.argb(191, 29, 30, 34), 4f)
        })
        val r = when (mode) { Mode.Kc -> 7f; Mode.Crt -> 6f; else -> 0f }
        val wp = paint(white).apply {
            when (mode) {
                Mode.Ir -> setShadowLayer(blur(14f), 0f, 0f, glow)
                Mode.Crt -> setShadowLayer(blur(12f), 0f, 0f, ink)
                Mode.Kc -> setShadowLayer(blur(7f), 0f, 1.5f, android.graphics.Color.argb(77, 60, 30, 10))
                else -> {}
            }
        }
        cv.drawRoundRect(-w / 2, -h / 2, w / 2, h / 2, r, r, wp)
        cv.save()
        begin(); rr(-w / 2 + bd, -h / 2 + bd, w - 2 * bd, h - 2 * bd, if (r > 0) 3f else 0f)
        cv.clipPath(Path(p)); cover(-w / 2 + bd, -h / 2 + bd, w - 2 * bd, h - 2 * bd)
        cv.restore()
        if (mode == Mode.Riso || mode == Mode.Cy) cv.drawRoundRect(-w / 2, -h / 2, w / 2, h / 2, r, r, strokePaint(argb(Palette.Ink), 1.4f))
        cv.restore()
    }

    private fun bird(x: Float, y: Float, s: Float) = stroke(R.Dark, 1.6f) { m(x - s, y); q(x - s / 2, y - s * 0.7f, x, y); q(x + s / 2, y - s * 0.7f, x + s, y) }

    private fun waves(y: Float, x0: Float, x1: Float, amp: Float, r: R, w: Float = 2f) = stroke(r, w) {
        var x = x0; while (x <= x1) { l(x, y + sin(x / 9) * amp); x += 2 }
    }

    private fun swing(period: Float, amp: Float) = sin(t * 2 * PI.toFloat() / period + ph) * amp

    private inline fun rotated(deg: Float, px: Float, py: Float, block: () -> Unit) {
        cv.save(); cv.rotate(deg, px, py); block(); cv.restore()
    }

    private fun rnd(seed: Int): () -> Float {
        var s = (seed * 7919 + 13) % 233280
        return { s = (s * 9301 + 49297) % 233280; s / 233280f }
    }

    // ---------- The scenes ----------

    fun draw() {
        cv.save()
        cv.translate(0f, swing(6f, 3f))
        when (look.kind) {
            IslandKind.Book -> book(); IslandKind.Mountain -> mountain(); IslandKind.Branch -> branch()
            IslandKind.Balloon -> balloon(); IslandKind.Kite -> kite(); IslandKind.Boat -> boat()
            IslandKind.Cloud -> cloud(); IslandKind.Moon -> moon(); IslandKind.Tree -> tree()
            IslandKind.Lighthouse -> lighthouse(); IslandKind.Tent -> tent(); IslandKind.Desert -> desert(); IslandKind.Pond -> pond()
        }
        cv.restore()
    }

    /** The album standing on its own ground, leaning a little: the page block behind, the cover photo on the front. */
    private fun book() {
        floorShadow(150f, 288f, 84f, 8f)
        fill(R.Stone2, O(shade = true)) {
            m(36f, 188f); q(78f, 222f, 110f, 236f); q(146f, 256f, 152f, 254f); q(196f, 232f, 226f, 216f); q(258f, 196f, 264f, 188f); z()
        }
        fill(R.Leaf) { oval(150f, 188f, 114f, 24f) }
        rotated(-6f + swing(9f, 0.6f), 150f, 186f) {
            val w = 94f; val h = 108f; val x = 152f - w / 2; val y = 128f - h / 2
            fill(R.Paper, O(line = 1.2f)) { rect(x + 8f, y + 6f, w, h) }
            for (i in 1..4) stroke(R.Dark, 0.8f, 0.4f) { m(x + w + 8f - i * 1.6f, y + 10f); l(x + w + 8f - i * 1.6f, y + h + 2f) }
            fill(R.Red, O(line = 1.4f)) { rect(x, y, w, h) }
            fill(R.Dark, O(flat = true, alpha = 0.35f, over = true)) { rect(x, y, 10f, h) }
            print(x + 14f + (w - 28f) / 2 + 4f, y + 15f + h * 0.26f, w - 28f, h * 0.52f, 0f, 3f)
            fill(R.Yellow, O(flat = true)) { rect(x + 19f, y + h * 0.76f, w - 36f, 7f) }
        }
    }

    private fun mountain() {
        floorShadow(150f, 280f, 80f, 9f)
        fill(R.Stone2, O(shade = true)) { m(52f, 200f); l(248f, 200f); l(212f, 226f); l(196f, 222f); l(170f, 256f); l(150f, 250f); l(126f, 270f); l(112f, 238f); l(84f, 230f); z() }
        fill(R.Stone) { m(48f, 202f); l(112f, 62f); l(138f, 104f); l(168f, 74f); l(252f, 202f); z() }
        fill(R.Snow, O(line = if (mode == Mode.Riso) 1f else 0f)) {
            m(112f, 62f); l(94f, 102f); l(106f, 96f); l(114f, 108f); l(124f, 96f); l(130f, 104f); z()
            m(168f, 74f); l(152f, 98f); l(162f, 94f); l(170f, 104f); l(180f, 92f); z()
        }
        for (i in 0 until 6) {
            val x = 62f + i * 30 + (i % 2) * 8; val y = 200f
            fill(if (i % 2 == 1) R.Leaf2 else R.Leaf) { m(x, y - 34 - (i % 3) * 6); l(x - 11, y); l(x + 11, y); z() }
        }
        stroke(R.Dark, 1.6f) { m(168f, 74f); l(296f, 40f) }
        rotated(swing(4f, 3f), 232f, 57f) {
            stroke(R.Dark, 2f) { m(232f, 57f); l(232f, 78f) }
            fill(R.Yellow, O(line = 1.6f, over = true)) { rr(196f, 78f, 72f, 60f, 8f) }
            fill(R.Red, O(flat = true, over = true)) { rect(197f, 124f, 70f, 13f) }
            photoIn(203f, 85f, 58f, 38f) { rr(203f, 85f, 58f, 38f, 4f) }
        }
    }

    private fun branch() {
        floorShadow(150f, 272f, 84f, 8f)
        fill(R.Wood, O(shade = true)) { m(18f, 122f); b(90f, 94f, 170f, 112f, 286f, 78f); l(288f, 88f); b(176f, 124f, 92f, 108f, 20f, 134f); z() }
        fill(R.Wood) { m(110f, 106f); q(116f, 74f, 140f, 58f); l(144f, 62f); q(122f, 80f, 118f, 106f); z() }
        val r = rnd(3)
        for (i in 0 until 16) {
            val x = 26f + i * 16 + r() * 8; val y = 110f - i * 1.6f + if (i % 2 == 1) -12 else 12; val a = (if (i % 2 == 1) -40f else 40f) + r() * 20
            fill(if (i % 3 != 0) R.Leaf else R.Leaf2) { turned(x, y, a) { m(-12f, 0f); q(0f, -8f, 12f, 0f); q(0f, 8f, -12f, 0f); z() } }
        }
        for (i in 0 until 7) {
            val x = 40f + r() * 230; val y = 88f + r() * 40
            fill(R.Pink) { circles(x, y, 5f) }
            fill(R.Yellow, O(flat = true)) { circles(x, y, 1.8f) }
        }
        rotated(swing(3.4f, 3f), 152f, 106f) {
            stroke(R.Dark, 1.4f) { m(112f, 110f); l(112f, 168f); m(192f, 102f); l(192f, 166f) }
            print(152f, 200f, 94f, 72f, 1.5f)
        }
        for ((x, y, f, role) in listOf(Quad(232f, 74f, 1f, R.Red), Quad(62f, 104f, -1f, R.Yellow))) {
            fill(role, O(over = true, line = 1.2f)) {
                m(x - 16 * f, y - 2); l(x - 26 * f, y - 8); l(x - 24 * f, y + 2); z()
                oval(x, y, 13f, 9f, if (f > 0) -0.2f else 0.2f)
                circles(x + 9 * f, y - 9, 7f)
            }
            fill(R.Orange, O(flat = true, over = true)) { m(x + 15 * f, y - 11); l(x + 22 * f, y - 8); l(x + 15 * f, y - 6); z() }
            fill(R.Dark, O(flat = true, over = true)) { circles(x + 11 * f, y - 10, 1.6f) }
            stroke(R.Dark, 1.2f) { m(x - 3, y + 8); l(x - 4, y + 14); m(x + 3, y + 8); l(x + 3, y + 14) }
        }
    }

    private fun balloon() {
        floorShadow(150f, 286f, 60f, 6f)
        cv.save(); cv.translate(0f, swing(5f, 5f)); cv.rotate(swing(7f, 2f), 150f, 80f)
        val env: () -> Unit = { m(150f, 152f); b(98f, 128f, 86f, 96f, 90f, 72f); arc(150f, 72f, 60f, PI.toFloat(), 0f); b(214f, 96f, 202f, 128f, 150f, 152f); z() }
        fill(R.Red, build = env)
        if (!lines) {
            begin(); env(); cv.save(); cv.clipPath(Path(p))
            for ((dx, role) in listOf(-38f to R.Yellow, 0f to R.Paper, 38f to R.Yellow)) fill(role, O(flat = true, over = true)) { oval(150 + dx, 86f, 12f, 72f) }
            cv.restore()
            stroke(R.Dark, 1.6f, build = env)
        } else for (dx in listOf(-38f, 0f, 38f)) stroke(R.Dark, 1f, 0.7f) { oval(150 + dx * 0.6f, 86f, abs(dx) * 0.5f + 2, 70f) }
        stroke(R.Dark, 1.4f) { m(118f, 140f); l(136f, 170f); m(182f, 140f); l(164f, 170f) }
        fill(R.Wood, O(shade = true, line = 1.2f)) { rr(132f, 168f, 36f, 22f, 3f) }
        stroke(R.Dark, 1.3f) { m(136f, 190f); l(120f, 206f); m(164f, 190f); l(180f, 206f) }
        print(150f, 236f, 88f, 64f, -2f)
        cv.restore()
        for ((x, y, s) in listOf(Triple(40f, 54f, 12f), Triple(256f, 110f, 9f))) {
            val d = swing(11f, 4f)
            fill(R.Paper, O(outline = 1.2f)) { circles(x + d, y, s, x + d + s, y - s * 0.4f, s * 0.9f, x + d + s * 1.9f, y, s * 0.8f) }
        }
    }

    private fun kite() {
        floorShadow(104f, 286f, 46f, 5f)
        stroke(R.Dark, 1.1f, 0.7f) { m(170f, 116f); q(80f, 170f, 40f, 296f) }
        rotated(swing(5f, 4f), 170f, 116f) {
            val dia: (Float) -> Unit = { i -> m(170f, 26 + i); l(254 - i, 116f); l(170f, 212 - i * 1.4f); l(86 + i, 116f); z() }
            fill(R.Red) { dia(0f) }
            photoIn(96f, 36f, 148f, 166f) { dia(10f) }
            stroke(R.Dark, 1.6f, 0.75f) { m(170f, 26f); l(170f, 212f); m(86f, 116f); l(254f, 116f) }
            val wave = swing(2.2f, 8f)
            stroke(R.Dark, 1.6f) { m(170f, 212f); b(150f + wave, 236f, 190f - wave, 250f, 170f, 280f) }
            for ((x, y, role) in listOf(Triple(166f, 232f, R.Yellow), Triple(176f, 256f, R.Leaf), Triple(168f, 276f, R.Pink))) fill(role) {
                m(x, y); l(x - 10, y - 7); l(x - 10, y + 7); z(); m(x, y); l(x + 10, y - 7); l(x + 10, y + 7); z()
            }
        }
    }

    private fun boat() {
        fill(R.Water, O(shade = true)) {
            m(30f, 226f); var x = 30f; while (x <= 270f) { l(x, 226 + sin(x / 11 + t) * 4); x += 4 }
            l(256f, 262f); q(150f, 286f, 44f, 262f); z()
        }
        rotated(swing(4.5f, 3f), 150f, 226f) {
            stroke(R.Dark, 2.2f) { m(150f, 214f); l(150f, 56f) }
            fill(R.Red) { m(150f, 56f); l(176f + swing(1.5f, 2f), 64f); l(150f, 72f); z() }
            photoIn(154f, 70f, 84f, 126f) { m(154f, 70f); l(154f, 196f); l(238f, 196f); z() }
            fill(R.Paper, O(line = 1.2f)) { m(146f, 96f); l(146f, 196f); l(86f, 196f); z() }
            fill(R.Paper, O(line = 1.4f)) { m(62f, 196f); l(240f, 196f); l(212f, 232f); l(90f, 232f); z() }
            fill(R.Paper, O(line = 1.2f, shade = true)) { m(112f, 196f); l(150f, 232f); l(188f, 196f); z() }
        }
        waves(244f, 52f, 248f, 3f, R.Paper, 1.6f)
        bird(70f, 70f, 10f); bird(96f, 52f, 7f)
    }

    private fun cloud() {
        fill(R.Yellow) { circles(226f, 66f, 26f) }
        val d = swing(10f, 3f)
        fill(R.Paper, O(outline = 1.6f)) { circles(72f + d, 150f, 34f, 110f + d, 128f, 40f, 150f + d, 146f, 32f, 96f + d, 170f, 30f, 134f + d, 174f, 28f) }
        fill(R.Stone2, O(alpha = 0.25f, flat = true)) { oval(112f + d, 186f, 62f, 10f) }
        fill(R.Paper, O(outline = 1.6f)) { circles(226f, 118f, 22f, 250f, 112f, 18f, 240f, 130f, 18f) }
        val fall = (t * 14f) % 12f
        stroke(R.Water, 2.4f) { for (i in 0 until 6) { val x = 74f + i * 14 + d; val y = 200f + (i % 2) * 12 + fall; m(x, y); l(x - 4, y + 12) } }
        stroke(R.Dark, 1.4f) { m(150f + d, 140f); q(190f, 176f, 226f, 124f) }
        rotated(swing(3f, 3f), 188f, 160f) {
            print(190f, 192f, 74f, 56f, 4f)
            for (x in listOf(168f, 208f)) fill(R.Wood, O(line = 1f)) { rect(x - 3, 152 + (x - 168) * 0.12f, 7f, 20f) }
        }
        floorShadow(140f, 282f, 80f, 7f)
    }

    private fun moon() {
        fill(R.Yellow, O(shade = true)) { arc(122f, 92f, 64f, 0.25f * PI.toFloat(), 1.75f * PI.toFloat()); arc(150f, 80f, 54f, 1.62f * PI.toFloat(), 0.42f * PI.toFloat(), ccw = true); z() }
        stroke(R.Dark, 1.8f) { arc(74f, 84f, 6f, 0.15f * PI.toFloat(), 0.85f * PI.toFloat()); started = false; m(70f, 104f); q(76f, 110f, 82f, 104f) }
        fill(R.Pink, O(alpha = 0.7f, flat = true)) { circles(66f, 98f, 4f) }
        rotated(swing(5f, 4f), 140f, 134f) {
            stroke(R.Dark, 1.1f, 0.75f) { m(160f, 134f); l(166f, 182f); m(124f, 152f); l(124f, 234f); m(100f, 146f); l(100f, 246f) }
            print(170f, 212f, 76f, 60f, -2f)
            for ((x, y, r, role) in listOf(Quad(124f, 244f, 9f, R.Yellow), Quad(100f, 256f, 10f, R.Pink))) fill(role) { star(x, y, r) }
        }
        for ((i, s) in listOf(Quad(236f, 60f, 6f, R.Yellow), Quad(58f, 180f, 5f, R.Paper), Quad(252f, 180f, 4f, R.Paper), Quad(40f, 40f, 4f, R.Paper)).withIndex()) {
            val tw = 0.75f + 0.25f * sin(t * 2.2f + i * 1.3f + ph)
            fill(s.d) { star(s.a, s.b, s.c * tw) }
        }
        floorShadow(150f, 288f, 60f, 5f)
    }

    private fun tree() {
        floorShadow(150f, 284f, 76f, 8f)
        fill(R.Wood, O(shade = true)) { m(84f, 236f); q(150f, 226f, 216f, 236f); q(180f, 276f, 150f, 272f); q(116f, 276f, 84f, 236f); z() }
        fill(R.Leaf2) { oval(150f, 236f, 68f, 10f) }
        fill(R.Wood) { m(140f, 236f); l(144f, 150f); l(156f, 150f); l(162f, 236f); z() }
        val sw = swing(6f, 1.5f)
        fill(R.Leaf, O(outline = 1.4f)) { circles(150f + sw, 86f, 52f, 100f + sw, 112f, 38f, 200f + sw, 110f, 40f, 126f + sw, 64f, 34f, 180f + sw, 62f, 32f) }
        fill(R.Leaf2) { circles(108f + sw, 128f, 22f, 196f + sw, 130f, 24f, 150f + sw, 132f, 26f) }
        for ((x, y) in listOf(96f to 96f, 206f to 92f, 160f to 44f, 124f to 140f)) fill(R.Red, O(flat = true)) { circles(x + sw, y, 4f) }
        fill(R.Wood, O(line = 1.4f, over = true)) { rect(104f, 112f, 92f, 70f) }
        fill(R.Red, O(line = 1.4f, over = true)) { m(96f, 114f); l(150f, 80f); l(204f, 114f); z() }
        photoIn(112f, 120f, 76f, 54f) { rect(112f, 120f, 76f, 54f) }
        stroke(R.Dark, 1.4f) { m(178f, 182f); l(186f, 234f); m(192f, 182f); l(200f, 234f); for (i in 0 until 5) { val y = 192f + i * 10; m(179 + i * 1.4f, y); l(193 + i * 1.4f, y) } }
    }

    private fun lighthouse() {
        val beam = 0.7f + 0.3f * sin(t * 0.9f + ph)
        cv.save()
        if (lines) stroke(R.Light, 1.2f, beam, dash = floatArrayOf(4f, 4f)) { m(82f, 82f); l(190f, 58f); m(82f, 94f); l(190f, 168f) }
        else {
            begin(); m(82f, 82f); l(190f, 56f); l(190f, 170f); l(82f, 94f); z()
            cv.drawPath(Path(p), paint(col(R.Light)).apply {
                alpha = (128 * beam).roundToInt()
                if (dark) blendMode = android.graphics.BlendMode.SCREEN else if (mode == Mode.Riso) blendMode = android.graphics.BlendMode.MULTIPLY
            })
        }
        cv.restore()
        print(234f, 112f, 96f, 110f, 2f, 4f)
        fill(R.Water, O(shade = true)) { oval(110f, 262f, 96f, 18f) }
        fill(R.Stone2, O(shade = true)) { circles(62f, 246f, 22f, 92f, 240f, 28f, 126f, 250f, 20f, 150f, 258f, 14f) }
        fill(R.Paper, O(line = 1.4f)) { m(66f, 232f); l(74f, 108f); l(98f, 108f); l(106f, 232f); z() }
        for (i in 0 until 3) fill(R.Red, O(flat = true)) { val y = 124f + i * 36; m(74.6f - i * 1.8f, y); l(97.4f + i * 1.8f, y); l(98.6f + i * 1.8f, y + 16); l(73.4f - i * 1.8f, y + 16); z() }
        fill(R.Dark, O(flat = true)) { rect(68f, 100f, 36f, 8f) }
        fill(R.Yellow, O(glow = 18f, line = 1.2f)) { rect(76f, 80f, 20f, 20f) }
        fill(R.Red, O(line = 1.2f)) { m(72f, 80f); l(86f, 62f); l(100f, 80f); z() }
        waves(280f, 30f, 190f, 2.5f, R.Water, 1.6f)
        bird(150f, 40f, 9f)
    }

    private fun tent() {
        floorShadow(150f, 284f, 104f, 9f)
        fill(R.Leaf2) { oval(150f, 244f, 112f, 24f) }
        fill(R.Wood, O(shade = true)) { m(40f, 248f); q(150f, 300f, 260f, 248f); q(150f, 276f, 40f, 248f); z() }
        stroke(R.Wood, 3f) { m(120f, 240f); l(120f, 54f); m(262f, 232f); l(262f, 54f) }
        fill(R.Paper, O(line = 1.2f)) { m(122f, 60f); q(191f, 70f, 260f, 60f); l(260f, 166f); l(122f, 166f); z() }
        photoIn(128f, 64f, 126f, 96f, line = false) { m(128f, 70f); q(191f, 79f, 254f, 70f); l(254f, 160f); l(128f, 160f); z() }
        fill(R.Pink, O(shade = true)) { m(34f, 236f); l(72f, 170f); l(110f, 236f); z() }
        fill(R.Dark, O(flat = true)) { m(62f, 236f); l(72f, 200f); l(82f, 236f); z() }
        stroke(R.Wood, 4f) { m(166f, 246f); l(204f, 236f); m(168f, 236f); l(204f, 246f) }
        val f = 0.85f + 0.15f * sin(t * 9f + ph) * sin(t * 5.3f)
        cv.save(); cv.scale(1f, f, 186f, 238f)
        fill(R.Orange, O(glow = 16f)) { m(170f, 238f); q(170f, 214f, 186f, 198f); q(186f, 216f, 200f, 238f); z() }
        fill(R.Yellow, O(flat = true)) { m(178f, 238f); q(180f, 222f, 188f, 214f); q(190f, 226f, 194f, 238f); z() }
        cv.restore()
        for ((i, s) in listOf(40f to 40f, 70f to 24f, 24f to 96f, 290f to 30f).withIndex()) {
            val tw = 0.7f + 0.3f * sin(t * 2.4f + i * 1.7f + ph)
            fill(R.Yellow) { star(s.first, s.second, 4f * tw) }
        }
    }

    private fun desert() {
        fill(R.Orange) { circles(86f, 98f, 52f) }
        floorShadow(150f, 284f, 100f, 8f)
        fill(R.Red, O(shade = true)) { m(34f, 226f); l(266f, 226f); l(234f, 248f); l(200f, 244f); l(176f, 272f); l(140f, 262f); l(110f, 276f); l(92f, 246f); l(58f, 244f); z() }
        fill(R.Sand) { m(30f, 228f); q(84f, 184f, 140f, 212f); q(200f, 176f, 270f, 228f); z() }
        fill(R.Sand, O(shade = true)) { m(60f, 228f); q(110f, 206f, 170f, 228f); z() }
        for (r in listOf(floatArrayOf(52f, 150f, 16f, 76f), floatArrayOf(36f, 170f, 12f, 32f), floatArrayOf(72f, 160f, 12f, 30f), floatArrayOf(40f, 192f, 20f, 8f), floatArrayOf(64f, 184f, 16f, 8f)))
            fill(R.Leaf, O(flat = true)) { rr(r[0], r[1], r[2], r[3], min(r[2], r[3]) / 2) }
        stroke(R.Wood, 4f) { m(150f, 214f); l(150f, 150f); m(240f, 214f); l(240f, 150f) }
        fill(R.Dark, O(flat = true)) { rect(114f, 70f, 162f, 88f) }
        photoIn(119f, 75f, 152f, 78f, line = false) { rect(119f, 75f, 152f, 78f) }
        for ((i, x) in listOf(140f, 195f, 250f).withIndex()) {
            stroke(R.Dark, 1.4f) { m(x, 70f); l(x, 62f); l(x + 8, 60f) }
            val on = sin(t * 1.6f + i * 2.1f + ph) > -0.6f
            fill(R.Yellow, O(glow = 10f, alpha = if (on) 1f else 0.35f)) { circles(x + 10, 60f, 3f) }
        }
    }

    private fun pond() {
        floorShadow(150f, 280f, 100f, 8f)
        fill(R.Wood, O(shade = true)) { ell(150f, 214f, 116f, 46f, 0f, 0f, PI.toFloat()); l(34f, 214f); z() }
        fill(R.Leaf2) { oval(150f, 206f, 118f, 44f) }
        fill(R.Water) { oval(150f, 204f, 104f, 36f) }
        for (i in 0 until 7) {
            val x = 60f + i * 5; val h = 40f + (i % 3) * 12; val lean = swing(3f + i * 0.4f, 3f)
            stroke(R.Leaf, 2.2f) { m(x, 190f); q(x - 6, 190 - h / 2, x + 2 + lean, 190 - h) }
        }
        fill(R.Wood) { for (x in listOf(62f, 70f)) oval(x, 146f, 4f, 10f) }
        for ((x, y, s) in listOf(Triple(226f, 196f, 15f), Triple(198f, 224f, 11f), Triple(88f, 222f, 13f))) fill(R.Leaf) {
            ell(x, y, s, s * 0.45f, 0f, 0.25f, (PI * 2 - 0.25).toFloat()); l(x, y); z()
        }
        for ((x, y) in listOf(226f to 188f, 90f to 214f)) fill(R.Pink) {
            for (i in 0 until 5) { val a = PI.toFloat() + i * PI.toFloat() / 4; oval(x + cos(a) * 5, y + sin(a) * 5, 6f, 3f, a) }
        }
        stroke(R.Paper, 1.4f, 0.8f) { oval(150f, 206f, 58f + swing(3f, 2f), 20f) }
        cv.save(); cv.translate(150f + swing(9f, 2f), 198f); cv.concat(Matrix().apply { setValues(floatArrayOf(1f, -0.35f, 0f, 0f, 0.55f, 0f, 0f, 0f, 1f)) })
        print(0f, 0f, 96f, 90f, swing(9f, 2f), 4f)
        cv.restore()
        fill(R.Leaf) { oval(184f, 176f, 13f, 9f) }
        fill(R.Paper, O(line = 1f)) { circles(178f, 168f, 4f, 190f, 168f, 4f) }
        fill(R.Dark, O(flat = true)) { circles(178f, 168f, 1.6f, 190f, 168f, 1.6f) }
        bird(236f, 60f, 9f)
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        private fun m(vararg pairs: Pair<R, Long>) = pairs.associate { it.first to it.second.toInt() }

        val RISO = m(
            R.Leaf to 0xFF00A95C, R.Leaf2 to 0xFF00838A, R.Water to 0xFF3255A4, R.Wood to 0xFFB48A5A, R.Stone to 0xFF3255A4, R.Stone2 to 0xFF5F6068,
            R.Red to 0xFFD4432A, R.Pink to 0xFFFF48B0, R.Yellow to 0xFFFFE800, R.Orange to 0xFFFF6C2F, R.Sand to 0xFFFFB511, R.Sky to 0xFF62A8E5,
            R.Snow to 0xFFF4F2ED, R.Light to 0xFFFFE800,
        )
        val RISO_DARK = RISO + m(R.Stone to 0xFF4A6BC0, R.Stone2 to 0xFF7E8299, R.Red to 0xFFE5553A, R.Sky to 0xFF3255A4)
        val IR = m(
            R.Leaf to 0xFFD3164F, R.Leaf2 to 0xFFFF4F9A, R.Water to 0xFF00A7BC, R.Wood to 0xFF6B1F33, R.Stone to 0xFF8E0E36, R.Stone2 to 0xFFB5476A,
            R.Red to 0xFFD3164F, R.Pink to 0xFFFF4F9A, R.Yellow to 0xFFE9D200, R.Orange to 0xFFFF7A4D, R.Sand to 0xFFF2B8C6, R.Sky to 0xFF00A7BC,
            R.Snow to 0xFFFFF9F6, R.Light to 0xFFE9D200,
        )
        val KC = m(
            R.Leaf to 0xFF3E8E3A, R.Leaf2 to 0xFF2F6E2C, R.Water to 0xFF2E7FB8, R.Wood to 0xFF8A5A36, R.Stone to 0xFF7C8794, R.Stone2 to 0xFF5E6670,
            R.Red to 0xFFD6282B, R.Pink to 0xFFE85D8A, R.Yellow to 0xFFF7B500, R.Orange to 0xFFF26B1D, R.Sand to 0xFFE8C27A, R.Sky to 0xFF7DB8E0,
            R.Snow to 0xFFFFFFFF, R.Light to 0xFFFFE08A,
        )
    }
}
