package io.github.darkxace01.attic.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds prints with a small neural network trained for exactly this (see `ml/` in the repo), instead of
 * hand-written rules about edges and colours.
 *
 * The network looks at the whole frame at 288 x 384 and marks, per pixel, print paper, each print's core
 * (the print shrunk to 60% about its centre, so neighbours never touch) and print outlines. Outlines come
 * from those maps: a patch of print paper that is one clean four-sided shape is one print, touching prints
 * are split between their cores, four corners are fitted to each print's pixels, and each side is then
 * settled where the print map crosses one half.
 * Mirrors `ml/quads.py`.
 *
 * Prints cut off by the frame edge are left out: they can't be captured whole.
 */
class PrintNet(model: ByteArray) {
    private val net: Net = Dnn.readNetFromONNX(MatOfByte(*model))

    /** The last frame's maps, for debugging and tests: print, core, edge, each [W] x [H]. */
    internal var lastMaps: Array<FloatArray>? = null

    /** Prints in [rgba], in its pixel coordinates. Thread-safe. */
    fun detect(rgba: Mat): List<Quad> {
        val maps = synchronized(this) { run(rgba) }
        lastMaps = maps
        val sx = rgba.cols().toFloat() / W
        val sy = rgba.rows().toFloat() / H
        // The maps are good to a pixel or two of their own size; settle each side on the real edge in the
        // frame, searching no further than that, so outlines are sharp and hold still from frame to frame.
        val search = 1.5f * max(sx, sy)
        return quads(maps).map { q ->
            val rough = q.scale(sx, sy)
            if (search >= 2f) EdgeSnap.snap(rgba, rough, search) else rough
        }
    }

    private fun run(rgba: Mat): Array<FloatArray> {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val blob = Dnn.blobFromImage(rgb, 1.0, Size(W.toDouble(), H.toDouble()), Scalar(0.0, 0.0, 0.0), false, false)
        rgb.release()
        net.setInput(blob)
        val out = net.forward()
        blob.release()
        val all = FloatArray(3 * W * H)
        out.reshape(1, 1).get(0, 0, all)
        out.release()
        return Array(3) { c -> all.copyOfRange(c * W * H, (c + 1) * W * H) }
    }

    companion object {
        const val W = 288
        const val H = 384
        const val CORE = 0.6f

        /**
         * Outlines from the maps, in map pixels. A patch of print paper holding two or more cores is split
         * between them (every pixel to the nearest core): prints touching, or stacked so closely they look
         * like one rectangle together. Each piece that is a clean four-sided shape is a print. If some
         * pieces aren't and the whole patch is, the cores were fragments of one print.
         */
        fun quads(maps: Array<FloatArray>, minAreaShare: Float = 0.004f): List<Quad> {
            val print = maps[0]
            val core = maps[1]
            val minArea = minAreaShare * W * H
            val paper = Mat(H, W, CvType.CV_8UC1).also { m -> m.put(0, 0, ByteArray(W * H) { if (print[it] > 0.5f) 1 else 0 }) }
            val compMat = Mat()
            val n = Imgproc.connectedComponents(paper, compMat, 4, CvType.CV_32S)
            paper.release()
            val comp = IntArray(W * H).also { compMat.get(0, 0, it) }
            compMat.release()
            val labels = if (core.any { it > 0.5f }) nearestCore(core) else null
            val out = mutableListOf<Quad>()
            for (c in 1 until n) {
                val patch = BooleanArray(W * H) { comp[it] == c }
                if (patch.count { it } < minArea) continue
                val whole = filledQuad(patch, 0.9f, 1.1f)
                var pieces = emptyList<Quad?>()
                if (labels != null) {
                    val coreSize = HashMap<Int, Int>()
                    for (i in 0 until W * H) if (patch[i] && core[i] > 0.5f) coreSize.merge(labels[i], 1, Int::plus)
                    val ks = coreSize.filter { it.value >= minArea * CORE * CORE }.keys
                    if (ks.size >= 2) pieces = ks.map { k -> filledQuad(BooleanArray(W * H) { patch[it] && labels[it] == k }, 0.85f, 1.15f) }
                }
                // Several cores: one print per core where its piece is a clean four-sided shape. If some
                // pieces aren't and the whole patch is, the cores were fragments of one print.
                val good = pieces.filterNotNull()
                if (whole != null && (pieces.isEmpty() || good.size < pieces.size)) out += refine(whole, print)
                else good.forEach { out += refine(it, print) }
            }
            return out.filter { inside(it, 1.5f) && it.isReasonable(60f, 120f) }.sortedBy { it.center.y * W + it.center.x }
        }

        /** For every pixel, which core it's nearest to. */
        private fun nearestCore(core: FloatArray): IntArray {
            val notCore = Mat(H, W, CvType.CV_8UC1).also { m -> m.put(0, 0, ByteArray(W * H) { if (core[it] > 0.5f) 0 else 1 }) }
            val dist = Mat()
            val labels = Mat()
            Imgproc.distanceTransformWithLabels(notCore, dist, labels, Imgproc.DIST_L2, 5, Imgproc.DIST_LABEL_CCOMP)
            val out = IntArray(W * H).also { labels.get(0, 0, it) }
            listOf(notCore, dist, labels).forEach { it.release() }
            return out
        }

        /** The four-sided outline of [region] if the region fills it, else null. */
        private fun filledQuad(region: BooleanArray, lo: Float, hi: Float): Quad? {
            val count = region.count { it }
            val m = Mat(H, W, CvType.CV_8UC1).also { m -> m.put(0, 0, ByteArray(W * H) { if (region[it]) 1 else 0 }) }
            val q = quadOfBlob(m)
            m.release()
            return q?.takeIf { it.area > 0f && count >= lo * it.area && count <= hi * it.area }
        }

        private fun quadOfBlob(mask: Mat): Quad? {
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            hierarchy.release()
            val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null
            val hullIdx = MatOfInt()
            Imgproc.convexHull(biggest, hullIdx)
            val pts = biggest.toArray()
            val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
            contours.forEach { it.release() }
            hullIdx.release()
            val peri = Imgproc.arcLength(hull, true)
            try {
                for (eps in doubleArrayOf(0.02, 0.03, 0.04, 0.06, 0.08)) {
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(hull, approx, eps * peri, true)
                    val corners = approx.toArray()
                    approx.release()
                    if (corners.size == 4) return Quad.fromCorners(corners.map { Pt(it.x.toFloat(), it.y.toFloat()) })
                }
                val box = Array(4) { Point() }
                Imgproc.minAreaRect(hull).points(box)
                return Quad.fromCorners(box.map { Pt(it.x.toFloat(), it.y.toFloat()) })
            } finally {
                hull.release()
            }
        }

        private fun sample(m: FloatArray, x: Float, y: Float): Float? {
            if (x < 0 || y < 0 || x > W - 1 || y > H - 1) return null
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = min(x0 + 1, W - 1); val y1 = min(y0 + 1, H - 1)
            val fx = x - x0; val fy = y - y0
            val top = m[y0 * W + x0] * (1 - fx) + m[y0 * W + x1] * fx
            val bottom = m[y1 * W + x0] * (1 - fx) + m[y1 * W + x1] * fx
            return top * (1 - fy) + bottom * fy
        }

        /** Moves each side to where the print map crosses 0.5, with a straight line through the crossings. */
        fun refine(q: Quad, print: FloatArray): Quad {
            val pts = q.points
            val c = q.center
            val lines = (0 until 4).map { i ->
                val a = pts[i]
                val b = pts[(i + 1) % 4]
                val len = a.dist(b)
                if (len < 4f) return q
                val d = (b - a) * (1f / len)
                var n = Pt(-d.y, d.x)
                val mid = (a + b) * 0.5f
                if ((c.x - mid.x) * n.x + (c.y - mid.y) * n.y > 0) n = n * -1f // outward
                val across = pts[(i + 1) % 4].dist(pts[(i + 2) % 4])
                val reach = max(3f, 0.12f * across)
                val found = ArrayList<Pt>()
                for (k in 0 until 24) {
                    val p = a + (b - a) * (0.1f + 0.8f * k / 23f)
                    var prevS = 0f
                    var prevV: Float? = null
                    var best: Float? = null
                    var s = -reach
                    while (s <= reach + 0.01f) {
                        val v = sample(print, p.x + n.x * s, p.y + n.y * s) ?: break
                        val pv = prevV
                        if (pv != null && pv >= 0.5f && v < 0.5f) {
                            val s0 = prevS + (pv - 0.5f) / (pv - v) * 0.5f
                            if (best == null || abs(s0) < abs(best)) best = s0
                        }
                        prevS = s; prevV = v
                        s += 0.5f
                    }
                    if (best != null) found += p + n * best
                }
                if (found.size >= 6) EdgeFit.robustLine(found) ?: Line(a, d) else Line(a, d)
            }
            val corners = (0 until 4).map { i -> lines[(i + 3) % 4].intersect(lines[i]) ?: return q }
            val out = Quad(corners[0], corners[1], corners[2], corners[3])
            val limit = 0.25f * min(pts[0].dist(pts[1]), pts[0].dist(pts[3]))
            return if (out.maxCornerShift(q) > limit) q else out
        }

        private fun inside(q: Quad, margin: Float) =
            q.points.all { it.x >= margin && it.y >= margin && it.x <= W - 1 - margin && it.y <= H - 1 - margin }
    }
}
