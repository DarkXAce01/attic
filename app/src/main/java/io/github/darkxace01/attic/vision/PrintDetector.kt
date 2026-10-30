package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds rectangular prints in an RGBA image. Returns quads in the input image's pixel coordinates.
 *
 * Real album pages are hard: patterned or striped paper, prints barely different from the page, glare,
 * plastic sleeves. So candidates come from three independent places, and every candidate has to prove
 * itself before it's kept:
 *
 *  1. **Colour edges, fine and coarse.** Edges are taken from whichever of lightness and the two colour
 *     channels changes most at each pixel, so a print the same brightness as the page still has an edge.
 *     The coarse pass blurs away stripes and patterns first.
 *  2. **Colour segmentation.** The page's own colour is learned from the frame (the most common colour
 *     around the border, or inside a page), and anything that differs from it is a print. Blurring first
 *     turns a patterned page into one even colour.
 *  3. **Verification.** Each candidate is snapped onto the nearest real edges, then every side must sit
 *     on an edge *and* have a different colour inside than outside. Stripes and flowers fail that test,
 *     prints pass it.
 */
class PrintDetector(
    private val workSize: Int = 640,
    /** Smallest print, as a share of the image area. */
    private val minAreaShare: Float = 0.012f,
) {
    /** One frame, shrunk to work size, with what the verification samples. */
    private class Frame(
        val w: Int,
        val h: Int,
        /** Lab, lightly smoothed, 3 bytes per pixel (L, a, b; a and b centred on 128). */
        val lab: ByteArray,
        /** Colour gradient: per pixel, the strongest of the L, a and b gradients (a, b weighted ×2). */
        val gx: FloatArray,
        val gy: FloatArray,
        /** A gradient strength that counts as a real edge in this frame. */
        val edgeStrength: Float,
    ) {
        fun inside(x: Int, y: Int) = x in 0 until w && y in 0 until h

        fun grad(x: Int, y: Int): Pair<Float, Float>? = if (inside(x, y)) gx[y * w + x] to gy[y * w + x] else null

        fun labAt(x: Int, y: Int): Triple<Int, Int, Int>? {
            if (!inside(x, y)) return null
            val i = (y * w + x) * 3
            return Triple(lab[i].toInt() and 255, lab[i + 1].toInt() and 255, lab[i + 2].toInt() and 255)
        }
    }

    /** Test hook: sees every candidate with its source and verification score (null = rejected). */
    internal var trace: ((source: String, quad: Quad, score: Float?) -> Unit)? = null
    private var lastSides = ""

    fun detect(rgba: Mat): List<Quad> {
        val scale = min(1.0, workSize.toDouble() / max(rgba.cols(), rgba.rows()))
        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(rgba, small, Size(rgba.cols() * scale, rgba.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            rgba.copyTo(small)
        }
        val rgb = Mat()
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        val lab = Mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        listOf(small, rgb).forEach { it.release() }
        val w = lab.cols()
        val h = lab.rows()

        val fineLab = Mat().also { Imgproc.GaussianBlur(lab, it, Size(0.0, 0.0), 1.0) }
        val coarseLab = Mat().also { Imgproc.GaussianBlur(lab, it, Size(0.0, 0.0), 3.0) }
        lab.release()

        val (fgx, fgy) = colorGradient(fineLab)
        val (cgx, cgy) = colorGradient(coarseLab)
        val fineBytes = ByteArray(w * h * 3).also { fineLab.get(0, 0, it) }
        val coarseBytes = ByteArray(w * h * 3).also { coarseLab.get(0, 0, it) }
        listOf(fineLab, coarseLab).forEach { it.release() }

        val fineMag = magnitudes(fgx, fgy)
        val coarseMag = magnitudes(cgx, cgy)
        val frame = Frame(w, h, fineBytes, fgx, fgy, edgeStrength = max(40f, percentile(fineMag, 0.80f) * 0.8f))
        val coarse = Frame(w, h, coarseBytes, cgx, cgy, edgeStrength = max(25f, percentile(coarseMag, 0.80f) * 0.8f))

        val raw = mutableListOf<Pair<String, Quad>>()
        edgeCandidates(fgx, fgy, fineMag, w, h, 0.90f).forEach { raw += "fine90" to it }
        edgeCandidates(fgx, fgy, fineMag, w, h, 0.75f).forEach { raw += "fine75" to it }
        edgeCandidates(cgx, cgy, coarseMag, w, h, 0.85f).forEach { raw += "coarse85" to it }
        segmentCandidates(coarse).forEach { raw += "segment" to it }

        val scored = raw.mapNotNull { (source, q) ->
            // Snap onto the real edges, and keep whichever of before/after verifies better.
            val snapped = EdgeFit.snap(q, 3f) { x, y -> frame.grad(x, y) }
            val a = verify(frame, coarse, q)
            val b = if (snapped.isReasonable()) verify(frame, coarse, snapped) else null
            if (trace != null) {
                verify(frame, coarse, q)
                trace?.invoke("$source $lastSides", q, a)
                if (snapped.isReasonable()) verify(frame, coarse, snapped)
                trace?.invoke("$source+snap $lastSides", snapped, b)
            }
            when {
                a == null && b == null -> null
                b != null && (a == null || b >= a) -> Candidates.Scored(snapped, b)
                else -> Candidates.Scored(q, a!!)
            }
        }
        val resolved = Candidates.resolveScored(scored) { outer, inner -> plainBetween(coarse, outer, inner) }
        resolved.forEach { trace?.invoke("resolved", it, null) }
        val prints = resolved.map { includeWhiteBorder(frame, it) }
        val inv = (1.0 / scale).toFloat()
        return prints.map { it.scale(inv, inv) }
    }

    // ---- candidates ----

    private fun edgeCandidates(gx: FloatArray, gy: FloatArray, mag: FloatArray, w: Int, h: Int, pct: Float): List<Quad> {
        val high = max(30f, percentile(mag, pct))
        val dx = Mat(h, w, CvType.CV_16SC1)
        val dy = Mat(h, w, CvType.CV_16SC1)
        dx.put(0, 0, ShortArray(gx.size) { gx[it].coerceIn(-32000f, 32000f).toInt().toShort() })
        dy.put(0, 0, ShortArray(gy.size) { gy[it].coerceIn(-32000f, 32000f).toInt().toShort() })
        val edges = Mat()
        Imgproc.Canny(dx, dy, edges, high * 0.4, high.toDouble(), true)
        listOf(dx, dy).forEach { it.release() }

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
        kernel.release()
        val out = contoursToQuads(edges, w, h, Imgproc.RETR_LIST)
        edges.release()
        return out
    }

    /**
     * Learns the page colour and keeps what differs from it. A big region (a page on a table) is searched
     * again inside, against its own colour.
     */
    private fun segmentCandidates(f: Frame): List<Quad> {
        val border = max(3, (min(f.w, f.h) * 0.03f).toInt())
        val bg = modeColor(f) { x, y -> x < border || y < border || x >= f.w - border || y >= f.h - border } ?: return emptyList()
        val mask = differenceMask(f, bg) { _, _ -> true }
        val out = mutableListOf<Quad>()
        val regions = components(mask, f.w, f.h)
        mask.release()
        for (region in regions) {
            val quad = toQuad(region.contour, Imgproc.contourArea(region.contour))
            if (quad != null) out += quad
            if (region.areaShare(f) > 0.25f) {
                // Probably a page: find the prints on it, against the page's own colour.
                val inner = region.mask(f.w, f.h, erodeBy = max(3, (min(f.w, f.h) * 0.02f).toInt()))
                val innerBytes = ByteArray(f.w * f.h).also { inner.get(0, 0, it) }
                inner.release()
                val inPage = { x: Int, y: Int -> innerBytes[y * f.w + x] != 0.toByte() }
                val pageColor = modeColor(f, inPage)
                if (pageColor != null) {
                    val m2 = differenceMask(f, pageColor, inPage)
                    components(m2, f.w, f.h).forEach { r -> toQuad(r.contour, Imgproc.contourArea(r.contour))?.let(out::add); r.contour.release() }
                    m2.release()
                }
            }
            region.contour.release()
        }
        return out
    }

    private class Region(val contour: MatOfPoint, val area: Double) {
        fun areaShare(f: Frame) = (area / (f.w.toDouble() * f.h)).toFloat()
        fun mask(w: Int, h: Int, erodeBy: Int): Mat {
            val m = Mat(h, w, CvType.CV_8UC1, Scalar(0.0))
            Imgproc.drawContours(m, listOf(contour), 0, Scalar(255.0), -1)
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(erodeBy * 2 + 1.0, erodeBy * 2 + 1.0))
            Imgproc.erode(m, m, k)
            k.release()
            return m
        }
    }

    private fun components(mask: Mat, w: Int, h: Int): List<Region> {
        val open = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
        val close = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(9.0, 9.0))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, open)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, close)
        listOf(open, close).forEach { it.release() }
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = w.toDouble() * h * minAreaShare
        return contours.mapNotNull { c ->
            val a = Imgproc.contourArea(c)
            if (a < minArea) { c.release(); null } else Region(c, a)
        }
    }

    /** Pixels whose colour differs from [ref] by more than the frame's own split (Otsu), within [where]. */
    private fun differenceMask(f: Frame, ref: Triple<Float, Float, Float>, where: (Int, Int) -> Boolean): Mat {
        val d = ByteArray(f.w * f.h)
        for (y in 0 until f.h) for (x in 0 until f.w) {
            if (!where(x, y)) continue
            val i = (y * f.w + x) * 3
            val dl = ((f.lab[i].toInt() and 255) - ref.first) * 0.6f
            val da = ((f.lab[i + 1].toInt() and 255) - ref.second) * 1.6f
            val db = ((f.lab[i + 2].toInt() and 255) - ref.third) * 1.6f
            d[y * f.w + x] = min(255f, sqrt(dl * dl + da * da + db * db) * 2f).toInt().toByte()
        }
        val m = Mat(f.h, f.w, CvType.CV_8UC1)
        m.put(0, 0, d)
        val out = Mat()
        val t = Imgproc.threshold(m, out, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        if (t < 24) Imgproc.threshold(m, out, 24.0, 255.0, Imgproc.THRESH_BINARY)
        m.release()
        return out
    }

    /** The most common colour among the pixels [where] says to look at (16 levels per channel). */
    private fun modeColor(f: Frame, where: (Int, Int) -> Boolean): Triple<Float, Float, Float>? {
        val counts = IntArray(4096)
        val sums = Array(4096) { LongArray(3) }
        for (y in 0 until f.h step 2) for (x in 0 until f.w step 2) {
            if (!where(x, y)) continue
            val i = (y * f.w + x) * 3
            val l = f.lab[i].toInt() and 255
            val a = f.lab[i + 1].toInt() and 255
            val b = f.lab[i + 2].toInt() and 255
            val bin = (l shr 4) * 256 + (a shr 4) * 16 + (b shr 4)
            counts[bin]++
            sums[bin][0] += l.toLong(); sums[bin][1] += a.toLong(); sums[bin][2] += b.toLong()
        }
        val best = counts.indices.maxBy { counts[it] }
        val n = counts[best]
        if (n == 0) return null
        return Triple(sums[best][0].toFloat() / n, sums[best][1].toFloat() / n, sums[best][2].toFloat() / n)
    }

    private fun contoursToQuads(binary: Mat, w: Int, h: Int, mode: Int): List<Quad> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val imageArea = w.toFloat() * h
        val out = mutableListOf<Quad>()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area >= imageArea * minAreaShare && area <= imageArea * 0.985f) toQuad(c, area)?.let(out::add)
            c.release()
        }
        return out
    }

    // ---- verification ----

    /**
     * How convincingly [q] is a print, 0..1, or null when it isn't one. Each side is sampled along its
     * length: does it sit on an edge (fine gradient across the side), and is the colour just inside
     * different from just outside (smoothed Lab)? Three sides must pass; the fourth may be weak (a print
     * edge that happens to match the page there).
     */
    private fun verify(fine: Frame, coarse: Frame, q: Quad): Float? {
        lastSides = ""
        if (!shapeOk(q, fine.w, fine.h)) return null
        val pts = q.points
        val c = q.center
        var passed = 0
        var total = 0f
        var weakest = 1f
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val len = a.dist(b)
            if (len < 6f) return null
            val d = (b - a) * (1f / len)
            var n = Pt(-d.y, d.x)
            val mid = (a + b) * 0.5f
            if ((c.x - mid.x) * n.x + (c.y - mid.y) * n.y < 0) n = n * -1f
            val samples = 16
            var onEdge = 0
            var counted = 0
            val contrasts = ArrayList<Float>(samples)
            for (k in 0 until samples) {
                val p = a + (b - a) * (0.12f + 0.76f * k / (samples - 1))
                var best = 0f
                var any = false
                for (s in -2..2) {
                    val g = fine.grad((p.x + n.x * s).roundToInt(), (p.y + n.y * s).roundToInt()) ?: continue
                    any = true
                    best = max(best, abs(g.first * n.x + g.second * n.y))
                }
                if (!any) continue
                counted++
                if (best >= fine.edgeStrength) onEdge++
                val inside = coarse.labAt((p.x + n.x * 6).roundToInt(), (p.y + n.y * 6).roundToInt())
                val outside = coarse.labAt((p.x - n.x * 6).roundToInt(), (p.y - n.y * 6).roundToInt())
                if (inside != null && outside != null) contrasts += labDistance(inside, outside)
            }
            if (counted < samples / 2) return null
            val support = onEdge.toFloat() / counted
            val contrast = if (contrasts.isEmpty()) 0f else contrasts.sorted()[contrasts.size / 2]
            val sideScore = 0.6f * support + 0.4f * min(1f, contrast / 18f)
            if (trace != null) lastSides += " [${"%.2f".format(support)} ${"%.0f".format(contrast)}]"
            if (support >= 0.5f && contrast >= 5f) passed++
            weakest = min(weakest, support)
            total += sideScore
        }
        if (passed < 3 || weakest < 0.25f) return null
        val score = total / 4f
        return if (score >= 0.55f) score else null
    }

    /**
     * Many prints have a white paper border, which on a cream or white page barely shows an edge, so the
     * print gets found by its image area alone. Step outward from each side: if there's a band of white
     * photo paper that then ends, the print really goes out to there.
     */
    private fun includeWhiteBorder(f: Frame, q: Quad): Quad {
        val pts = q.points
        val c = q.center
        val shifts = FloatArray(4)
        var bordered = 0
        for (i in 0 until 4) {
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val len = a.dist(b)
            if (len < 10f) return q
            val d = (b - a) * (1f / len)
            var n = Pt(-d.y, d.x)
            val mid = (a + b) * 0.5f
            if ((c.x - mid.x) * n.x + (c.y - mid.y) * n.y > 0) n = n * -1f // outward
            val across = if (i % 2 == 0) min(pts[0].dist(pts[3]), pts[1].dist(pts[2])) else min(pts[0].dist(pts[1]), pts[3].dist(pts[2]))
            val maxStep = (across * 0.25f).toInt().coerceAtLeast(3)
            var white = 0
            var ended = false
            for (step in 1..maxStep) {
                var whiteHere = 0
                var counted = 0
                for (k in 0 until 12) {
                    val p = a + (b - a) * (0.15f + 0.7f * k / 11f) + n * step.toFloat()
                    val lab = f.labAt(p.x.roundToInt(), p.y.roundToInt()) ?: continue
                    counted++
                    val chroma = abs(lab.second - 128) + abs(lab.third - 128)
                    if (lab.first >= 210 && chroma <= 16) whiteHere++
                }
                if (counted < 6) break
                if (whiteHere >= counted * 0.75f) {
                    white = step
                } else {
                    ended = white > 0
                    break
                }
            }
            if (ended && white >= 2) {
                shifts[i] = white + 0.5f
                bordered++
            }
        }
        // A real border shows on at least three sides; one white side is more likely sky in the photo.
        if (bordered < 3) return q
        val lines = (0 until 4).map { i ->
            val a = pts[i]
            val b = pts[(i + 1) % 4]
            val d = (b - a) * (1f / a.dist(b))
            var n = Pt(-d.y, d.x)
            val mid = (a + b) * 0.5f
            if ((c.x - mid.x) * n.x + (c.y - mid.y) * n.y > 0) n = n * -1f
            Line(a + n * shifts[i], d)
        }
        val corners = (0 until 4).map { i -> lines[(i + 3) % 4].intersect(lines[i]) ?: return q }
        val grown = Quad(corners[0], corners[1], corners[2], corners[3])
        return if (grown.isReasonable() && shapeOk(grown, f.w, f.h)) grown else q
    }

    /**
     * True when [outer] is a page holding the prints [inner], not a photo with rectangles in its picture.
     * A page is one colour between its prints (patterns blur out at the coarse scale); a picture isn't.
     */
    private fun plainBetween(f: Frame, outer: Quad, inner: List<Quad>): Boolean {
        val covered = inner.sumOf { it.area.toDouble() }.toFloat() / outer.area
        if (inner.size == 1 && covered > 0.6f) return false
        // Pages are full of prints; a picture's own rectangles are usually small details.
        if (inner.size >= 2 && covered >= 0.3f) return true
        val minX = outer.points.minOf { it.x }.toInt()
        val maxX = outer.points.maxOf { it.x }.toInt()
        val minY = outer.points.minOf { it.y }.toInt()
        val maxY = outer.points.maxOf { it.y }.toInt()
        val samples = ArrayList<Triple<Int, Int, Int>>()
        val step = max(2, (max(maxX - minX, maxY - minY) / 40))
        for (y in minY..maxY step step) for (x in minX..maxX step step) {
            val p = Pt(x.toFloat(), y.toFloat())
            if (!outer.contains(p) || inner.any { it.contains(p) }) continue
            f.labAt(x, y)?.let(samples::add)
        }
        if (samples.size < 20) return inner.size >= 2
        val counts = HashMap<Int, Int>()
        for (s in samples) counts.merge((s.first shr 4) * 256 + (s.second shr 4) * 16 + (s.third shr 4), 1, Int::plus)
        val bin = counts.maxBy { it.value }.key
        val modal = samples.filter { (it.first shr 4) * 256 + (it.second shr 4) * 16 + (it.third shr 4) == bin }
        val ref = Triple(modal.sumOf { it.first } / modal.size, modal.sumOf { it.second } / modal.size, modal.sumOf { it.third } / modal.size)
        val share = samples.count { labDistance(it, ref) < 14f }.toFloat() / samples.size
        return share >= 0.6f
    }

    private fun shapeOk(q: Quad, w: Int, h: Int): Boolean {
        if (!q.isReasonable()) return false
        val area = q.area
        if (area < w.toFloat() * h * minAreaShare || area > w.toFloat() * h * 0.985f) return false
        val longSide = max(q.outputWidth, q.outputHeight)
        val shortSide = min(q.outputWidth, q.outputHeight)
        if (shortSide <= 0f || longSide / shortSide > 4f) return false
        // A print cut off by the frame edge can't be cropped properly; wait until it's fully in view.
        val margin = 0.01f * max(w, h)
        val onEdge = q.points.count { it.x < margin || it.y < margin || it.x > w - margin || it.y > h - margin }
        return onEdge < 2
    }

    // ---- helpers ----

    private fun labDistance(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Float {
        val dl = (a.first - b.first) * 0.6f
        val da = (a.second - b.second) * 1.6f
        val db = (a.third - b.third) * 1.6f
        return sqrt(dl * dl + da * da + db * db)
    }

    /** Per pixel, the gradient of whichever Lab channel changes most (colour channels weighted ×2). */
    private fun colorGradient(lab: Mat): Pair<FloatArray, FloatArray> {
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        val n = lab.cols() * lab.rows()
        val gx = FloatArray(n)
        val gy = FloatArray(n)
        val bestMag = FloatArray(n)
        val weights = floatArrayOf(1f, 2f, 2f)
        val dx = Mat()
        val dy = Mat()
        val ax = FloatArray(n)
        val ay = FloatArray(n)
        for ((ci, ch) in channels.withIndex()) {
            Imgproc.Sobel(ch, dx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(ch, dy, CvType.CV_32F, 0, 1, 3)
            dx.get(0, 0, ax)
            dy.get(0, 0, ay)
            val wgt = weights[ci]
            for (i in 0 until n) {
                val x = ax[i] * wgt
                val y = ay[i] * wgt
                val m = x * x + y * y
                if (m > bestMag[i]) { bestMag[i] = m; gx[i] = x; gy[i] = y }
            }
            ch.release()
        }
        dx.release(); dy.release()
        return gx to gy
    }

    private fun magnitudes(gx: FloatArray, gy: FloatArray) = FloatArray(gx.size) { hypot(gx[it], gy[it]) }

    private fun percentile(values: FloatArray, p: Float): Float {
        val step = max(1, values.size / 20000)
        val sample = FloatArray((values.size + step - 1) / step) { values[min(values.size - 1, it * step)] }
        sample.sort()
        return sample[((sample.size - 1) * p).toInt()]
    }

    private fun toQuad(contour: MatOfPoint, area: Double): Quad? {
        val pts = contour.toArray()
        if (pts.size < 4) return null
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        hullIdx.release()
        val perimeter = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        var quad: Quad? = null
        // Blobby corners (from segmentation, glare, a worn print) need a looser fit; try tight to loose.
        for (eps in doubleArrayOf(0.012, 0.02, 0.03, 0.045, 0.06)) {
            Imgproc.approxPolyDP(hull, approx, eps * perimeter, true)
            if (approx.total() == 4L) {
                quad = Quad.fromCorners(approx.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) })
                break
            }
            if (approx.total() < 4L) break
        }
        if (quad == null) {
            val rect = Imgproc.minAreaRect(hull)
            val rectArea = rect.size.width * rect.size.height
            if (rectArea > 0 && area / rectArea >= 0.85) {
                quad = Array(4) { Point() }.also(rect::points).map { Pt(it.x.toFloat(), it.y.toFloat()) }.let(Quad::fromCorners)
            }
        }
        hull.release()
        approx.release()
        return quad
    }

    /**
     * Re-detects one print at higher resolution inside [region] of a full-size image, starting from a
     * rough [guess] in full-image pixels. Returns the refined quad, or the guess when nothing matches.
     */
    fun refine(full: Mat, guess: Quad): Quad {
        val pad = 0.06f
        val minX = guess.points.minOf { it.x }
        val maxX = guess.points.maxOf { it.x }
        val minY = guess.points.minOf { it.y }
        val maxY = guess.points.maxOf { it.y }
        val padX = (maxX - minX) * pad
        val padY = (maxY - minY) * pad
        val x0 = (minX - padX).roundToInt().coerceIn(0, full.cols() - 1)
        val y0 = (minY - padY).roundToInt().coerceIn(0, full.rows() - 1)
        val x1 = (maxX + padX).roundToInt().coerceIn(x0 + 1, full.cols())
        val y1 = (maxY + padY).roundToInt().coerceIn(y0 + 1, full.rows())
        val roi = full.submat(Rect(x0, y0, x1 - x0, y1 - y0))

        val local = PrintDetector(workSize = 1400, minAreaShare = 0.25f).detect(roi)
        roi.release()

        val guessLocal = guess.map { Pt(it.x - x0, it.y - y0) }
        val best = local.maxByOrNull { it.iou(guessLocal) }?.takeIf { it.iou(guessLocal) >= 0.75f }
        val rough = best?.map { Pt(it.x + x0, it.y + y0) } ?: guess
        // The re-detection is good to a few pixels; the rough guess from the preview needs a wider search.
        val search = EdgeFit.diagonal(rough) * (if (best != null) 0.01f else 0.025f)
        return snapEdges(full, rough, search.coerceAtLeast(4f))
    }

    /** Fits each side of [quad] to the real edge in [full] at full resolution. */
    fun snapEdges(full: Mat, quad: Quad, searchPx: Float): Quad {
        val r = searchPx.toInt() + 3
        val x0 = (quad.points.minOf { it.x }.toInt() - r).coerceIn(0, full.cols() - 1)
        val y0 = (quad.points.minOf { it.y }.toInt() - r).coerceIn(0, full.rows() - 1)
        val x1 = (quad.points.maxOf { it.x }.toInt() + r).coerceIn(x0 + 1, full.cols())
        val y1 = (quad.points.maxOf { it.y }.toInt() + r).coerceIn(y0 + 1, full.rows())
        val w = x1 - x0
        val h = y1 - y0

        val roi = full.submat(Rect(x0, y0, w, h))
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
        roi.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
        val ax = FloatArray(w * h).also { gx.get(0, 0, it) }
        val ay = FloatArray(w * h).also { gy.get(0, 0, it) }
        listOf(gray, gx, gy).forEach { it.release() }

        val local = quad.map { Pt(it.x - x0, it.y - y0) }
        val snapped = EdgeFit.snap(local, searchPx) { x, y ->
            if (x < 0 || y < 0 || x >= w || y >= h) null else ax[y * w + x] to ay[y * w + x]
        }
        return snapped.map { Pt(it.x + x0, it.y + y0) }
    }

}

object Rectify {
    /** Warps [quad] (pixels of [src]) to a flat, upright rectangle at full resolution. */
    fun warp(src: Mat, quad: Quad, maxSide: Int = Int.MAX_VALUE): Mat {
        var w = quad.outputWidth
        var h = quad.outputHeight
        val fit = maxSide / max(w, h)
        if (fit < 1f) { w *= fit; h *= fit }
        val outW = w.roundToInt().coerceAtLeast(1)
        val outH = h.roundToInt().coerceAtLeast(1)

        val from = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        val interp = if (fit < 1f) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        Imgproc.warpPerspective(src, out, m, Size(outW.toDouble(), outH.toDouble()), interp, Core.BORDER_REPLICATE)
        listOf(from, to, m).forEach { it.release() }
        return out
    }

    /**
     * Sharpness as the variance of the Laplacian, measured at a fixed size so prints of different
     * resolutions compare fairly. Higher is sharper; under ~60 usually looks soft.
     */
    fun sharpness(rgba: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val scale = 1000.0 / max(gray.cols(), gray.rows())
        if (scale < 1.0) Imgproc.resize(gray, gray, Size(), scale, scale, Imgproc.INTER_AREA)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val sd = std.toArray()[0]
        listOf(gray, lap, mean, std).forEach { it.release() }
        return sd * sd
    }
}
