package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Prints in albums curl: lifted in the middle, their straight sides photograph as gentle bows, and a
 * plain four-corner flatten leaves the picture bent with a sliver of page along the bowed sides.
 *
 * After the four-corner flatten (with a margin around it), each side's real edge is found at many points
 * along it and a smooth curve (a parabola) is fitted through them. When a side bows by more than a hair,
 * the print is mapped from its four curved sides onto a rectangle (a Coons patch), which straightens the
 * picture inside as well. A side whose edge can't be found clearly is taken as straight.
 */
object Curl {
    /** The print at [quad] in [src] (pixels), uncurled; null when it isn't curled (use [Rectify.warp]). */
    fun flatten(src: Mat, quad: Quad): Mat? {
        val bows = measure(src, quad) ?: return null
        return unbend(src, quad, bows)
    }

    /**
     * How each side bows, as parabola coefficients for its outward offset (in flat pixels) along it,
     * t in 0..1: top, right, bottom, left. Null when no side bows enough to matter.
     */
    internal fun measure(src: Mat, quad: Quad): List<DoubleArray>? {
        val w = quad.outputWidth.toDouble(); val h = quad.outputHeight.toDouble()
        // Edges are looked for at a working size: the long side about 1200 px.
        val s = min(1.0, WORK / max(w, h))
        val ww = w * s; val hh = h * s
        val m = max(ww, hh) * MARGIN
        val canvas = warpWithMargin(src, quad, ww, hh, m, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(canvas, gray, if (canvas.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        canvas.release()
        Imgproc.GaussianBlur(gray, gray, Size(0.0, 0.0), 1.2)
        val gx = Mat(); val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
        val cw = gray.cols(); val ch = gray.rows()
        gray.release()
        val ax = FloatArray(cw * ch).also { gx.get(0, 0, it) }
        val ay = FloatArray(cw * ch).also { gy.get(0, 0, it) }
        gx.release(); gy.release()
        fun g(x: Int, y: Int, horizontalEdge: Boolean): Float {
            if (x < 0 || y < 0 || x >= cw || y >= ch) return 0f
            return abs(if (horizontalEdge) ay[y * cw + x] else ax[y * cw + x])
        }

        val bows = ArrayList<DoubleArray>()
        var curled = false
        for (side in 0 until 4) {
            val horizontal = side % 2 == 0
            val len = if (horizontal) ww else hh
            val band = (m * 0.9).toInt()
            val samples = ArrayList<Pair<Double, Double>>()
            for (k in 0 until SAMPLES) {
                val t = 0.04 + 0.92 * k / (SAMPLES - 1)
                // The straight side's position at t, and which way is out.
                val (x0, y0) = when (side) {
                    0 -> m + t * ww to m
                    1 -> m + ww to m + t * hh
                    2 -> m + t * ww to m + hh
                    else -> m to m + t * hh
                }
                val (ox, oy) = when (side) { 0 -> 0 to -1; 1 -> 1 to 0; 2 -> 0 to 1; else -> -1 to 0 }
                var best = 0f; var bestD = 0
                val gs = FloatArray(2 * band + 1)
                for (d in -band..band) {
                    // A few pixels along the side, so a speck doesn't count as the edge.
                    var sum = 0f
                    for (a in -2..2) {
                        val px = (x0 + ox * d + (if (horizontal) a else 0)).roundToInt()
                        val py = (y0 + oy * d + (if (horizontal) 0 else a)).roundToInt()
                        sum += g(px, py, horizontal)
                    }
                    gs[d + band] = sum
                    if (sum > best) { best = sum; bestD = d }
                }
                if (best < MIN_EDGE * 5) continue
                // Sub-pixel: a parabola through the peak and its neighbours.
                val i = bestD + band
                val off = if (i in 1 until gs.size - 1) {
                    val l = gs[i - 1]; val c = gs[i]; val r = gs[i + 1]
                    val den = l - 2 * c + r
                    if (den != 0f) (0.5f * (l - r) / den).toDouble().coerceIn(-0.5, 0.5) else 0.0
                } else 0.0
                samples += t to (bestD + off)
            }
            val fit = robustParabola(samples)
            if (fit == null) { bows += DoubleArray(0); continue }
            // Bow: how far the middle sits from the line between the ends.
            val bow = abs(-fit[2] / 4)
            if (bow > max(MIN_BOW_PX, MIN_BOW * len)) curled = true
            // Back in flat pixels.
            bows += DoubleArray(3) { fit[it] / s }
        }
        if (!curled) return null
        // A side whose edge couldn't be found (a dark print on a dark page) bows like the side across from
        // it: a print lifting in the middle comes closer to the camera, so it bulges out on both sides alike.
        return List(4) { side ->
            bows[side].takeIf { it.isNotEmpty() }
                ?: bows[(side + 2) % 4].takeIf { it.isNotEmpty() }?.let { o -> doubleArrayOf(0.0, -o[2], o[2]) }
                ?: doubleArrayOf(0.0, 0.0, 0.0)
        }
    }

    /** The print mapped from its four curved sides onto a rectangle, at full resolution. */
    internal fun unbend(src: Mat, quad: Quad, bows: List<DoubleArray>): Mat {
        val w = quad.outputWidth.toDouble(); val h = quad.outputHeight.toDouble()
        fun off(side: Int, t: Double) = bows[side].let { it[0] + it[1] * t + it[2] * t * t }
        // The four sides as curves in the straight flatten's space (0..w, 0..h), offsets pointing out.
        fun top(u: Double) = doubleArrayOf(u * w, -off(0, u))
        fun right(v: Double) = doubleArrayOf(w + off(1, v), v * h)
        fun bottom(u: Double) = doubleArrayOf(u * w, h + off(2, u))
        fun left(v: Double) = doubleArrayOf(-off(3, v), v * h)
        // Where the curves meet: each corner the average of its two sides' ends.
        fun mid(a: DoubleArray, b: DoubleArray) = doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        val c00 = mid(top(0.0), left(0.0)); val c10 = mid(top(1.0), right(0.0))
        val c01 = mid(bottom(0.0), left(1.0)); val c11 = mid(bottom(1.0), right(1.0))
        // Each curve nudged (linearly along it) so its ends land on the shared corners.
        fun fixed(p: DoubleArray, t: Double, a0: DoubleArray, a1: DoubleArray, e0: DoubleArray, e1: DoubleArray) =
            doubleArrayOf(p[0] + (1 - t) * (e0[0] - a0[0]) + t * (e1[0] - a1[0]), p[1] + (1 - t) * (e0[1] - a0[1]) + t * (e1[1] - a1[1]))
        fun T(u: Double) = fixed(top(u), u, top(0.0), top(1.0), c00, c10)
        fun B(u: Double) = fixed(bottom(u), u, bottom(0.0), bottom(1.0), c01, c11)
        fun L(v: Double) = fixed(left(v), v, left(0.0), left(1.0), c00, c01)
        fun R(v: Double) = fixed(right(v), v, right(0.0), right(1.0), c10, c11)

        // The picture's real size: the curves' lengths, averaged.
        fun length(f: (Double) -> DoubleArray): Double {
            var sum = 0.0; var prev = f(0.0)
            for (k in 1..64) { val p = f(k / 64.0); sum += hypot(p[0] - prev[0], p[1] - prev[1]); prev = p }
            return sum
        }
        val outW = ((length(::T) + length(::B)) / 2).roundToInt().coerceAtLeast(1)
        val outH = ((length(::L) + length(::R)) / 2).roundToInt().coerceAtLeast(1)

        // Straight-flatten space back to the photo: the inverse of the four-corner homography.
        val from = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
        val to = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val hm = Imgproc.getPerspectiveTransform(from, to)
        val H = DoubleArray(9).also { hm.get(0, 0, it) }
        listOf(from, to, hm).forEach { it.release() }

        // The map on a coarse grid, then smoothly enlarged: the patch is smooth, so this is exact enough.
        val step = 8
        val gw = outW / step + 2; val gh = outH / step + 2
        val mx = Mat(gh, gw, CvType.CV_32F); val my = Mat(gh, gw, CvType.CV_32F)
        val bx = FloatArray(gw * gh); val by = FloatArray(gw * gh)
        for (j in 0 until gh) for (i in 0 until gw) {
            val u = (i * step).toDouble() / outW; val v = (j * step).toDouble() / outH
            val t = T(u); val b = B(u); val l = L(v); val r = R(v)
            val x = (1 - v) * t[0] + v * b[0] + (1 - u) * l[0] + u * r[0] -
                ((1 - u) * (1 - v) * c00[0] + u * (1 - v) * c10[0] + (1 - u) * v * c01[0] + u * v * c11[0])
            val y = (1 - v) * t[1] + v * b[1] + (1 - u) * l[1] + u * r[1] -
                ((1 - u) * (1 - v) * c00[1] + u * (1 - v) * c10[1] + (1 - u) * v * c01[1] + u * v * c11[1])
            val d = H[6] * x + H[7] * y + H[8]
            bx[j * gw + i] = ((H[0] * x + H[1] * y + H[2]) / d).toFloat()
            by[j * gw + i] = ((H[3] * x + H[4] * y + H[5]) / d).toFloat()
        }
        mx.put(0, 0, bx); my.put(0, 0, by)
        val fullX = Mat(); val fullY = Mat()
        // Grid node i sits at pixel i * step: enlarge by step, keeping node 0 on pixel 0.
        Imgproc.resize(mx, fullX, Size((gw * step).toDouble(), (gh * step).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(my, fullY, Size((gw * step).toDouble(), (gh * step).toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val shiftedX = Mat(); val shiftedY = Mat()
        // Resizing puts node i at pixel i * step + (step - 1) / 2; read each pixel from that far on.
        val shift = Mat(2, 3, CvType.CV_64F).also { it.put(0, 0, 1.0, 0.0, (step - 1) / 2.0, 0.0, 1.0, (step - 1) / 2.0) }
        Imgproc.warpAffine(fullX, shiftedX, shift, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        Imgproc.warpAffine(fullY, shiftedY, shift, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        val out = Mat()
        Imgproc.remap(src, out, shiftedX, shiftedY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        listOf(mx, my, fullX, fullY, shiftedX, shiftedY, shift).forEach { it.release() }
        return out
    }

    /** The straight flatten of [quad], [w] x [h], inside a margin of [m] all round. */
    private fun warpWithMargin(src: Mat, quad: Quad, w: Double, h: Double, m: Double, interp: Int): Mat {
        val from = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val to = MatOfPoint2f(Point(m, m), Point(m + w, m), Point(m + w, m + h), Point(m, m + h))
        val hm = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(src, out, hm, Size(w + 2 * m, h + 2 * m), interp, Core.BORDER_REPLICATE)
        listOf(from, to, hm).forEach { it.release() }
        return out
    }

    /**
     * a + b t + c t² through ([t], offset) points, refitted twice without the points furthest off (edges
     * of other prints, a thumb). Null if too few points agree.
     */
    internal fun robustParabola(points: List<Pair<Double, Double>>): DoubleArray? {
        var pts = points
        if (pts.size < SAMPLES * 0.5) return null
        var fit = DoubleArray(3)
        repeat(3) { round ->
            fit = lsq(pts) ?: return null
            val res = pts.map { (t, d) -> abs(d - (fit[0] + fit[1] * t + fit[2] * t * t)) }
            val limit = if (round == 0) max(3.0, 2.5 * median(res)) else max(1.5, 2.5 * median(res))
            pts = pts.filterIndexed { i, _ -> res[i] <= limit }
            if (pts.size < SAMPLES * 0.5) return null
        }
        fit = lsq(pts) ?: return null
        val rms = sqrt(pts.sumOf { (t, d) -> (d - (fit[0] + fit[1] * t + fit[2] * t * t)).let { it * it } } / pts.size)
        return if (rms <= MAX_RMS) fit else null
    }

    private fun median(v: List<Double>) = v.sorted()[v.size / 2]

    private fun lsq(pts: List<Pair<Double, Double>>): DoubleArray? {
        // Normal equations for 3 unknowns.
        val a = Array(3) { DoubleArray(3) }; val b = DoubleArray(3)
        for ((t, d) in pts) {
            val row = doubleArrayOf(1.0, t, t * t)
            for (i in 0..2) { b[i] += row[i] * d; for (j in 0..2) a[i][j] += row[i] * row[j] }
        }
        // Gaussian elimination.
        for (c in 0..2) {
            val p = (c..2).maxBy { abs(a[it][c]) }
            if (abs(a[p][c]) < 1e-12) return null
            a[c] = a[p].also { a[p] = a[c] }; b[c] = b[p].also { b[p] = b[c] }
            for (r in 0..2) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c..2) a[r][k] -= f * a[c][k]
                b[r] -= f * b[c]
            }
        }
        return DoubleArray(3) { b[it] / a[it][it] }
    }

    private const val WORK = 1200.0
    /** Room around the print to look for its edges in, as a share of its long side. */
    private const val MARGIN = 0.05
    private const val SAMPLES = 31
    /** Weakest edge (summed Sobel over 5 px) that counts as the print's edge. */
    private const val MIN_EDGE = 40f
    /** A side has to bow at least this much (working pixels, and share of its length) to be uncurled. */
    private const val MIN_BOW_PX = 3.0
    private const val MIN_BOW = 0.004
    /** How closely the edge points have to follow the fitted curve, working pixels. */
    private const val MAX_RMS = 1.6
}
