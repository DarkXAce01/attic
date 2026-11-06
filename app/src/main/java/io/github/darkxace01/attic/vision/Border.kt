package io.github.darkxace01.attic.vision

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The plain border around a print: the white (or cream, or black) margin of an old print, or a Polaroid's
 * frame with its deep bottom. Found on the flattened print by walking in from each side while whole rows
 * are still the border's colour, so it's only found when it really runs all the way round.
 */
object Border {
    /**
     * How deep the border is on each side, as a share of the print's width (left, right) or height (top,
     * bottom). [writing]: something is written or printed on it (a date, a name under a Polaroid), which
     * trimming would lose.
     */
    data class Inset(val left: Float, val top: Float, val right: Float, val bottom: Float, val writing: Boolean)

    /** The border of [rgba] (a flattened print), or null if it has none all the way round. */
    fun find(rgba: Mat): Inset? {
        val s = min(1.0, WORK / max(rgba.cols(), rgba.rows()))
        val small = Mat()
        Imgproc.resize(rgba, small, Size(rgba.cols() * s, rgba.rows() * s), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.cvtColor(small, small, if (rgba.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB)
        Imgproc.cvtColor(small, small, Imgproc.COLOR_RGB2Lab)
        val w = small.cols(); val h = small.rows()
        val px = ByteArray(w * h * 3).also { small.get(0, 0, it) }
        small.release()
        fun at(x: Int, y: Int, c: Int) = px[(y * w + x) * 3 + c].toInt() and 0xFF

        // The border's colour: the median of a thin strip all round, just inside the very edge.
        val edge = max(1, (min(w, h) * 0.004).toInt())
        val strip = max(edge + 2, (min(w, h) * 0.015).toInt())
        val samples = ArrayList<IntArray>()
        for (y in edge until h - edge) for (x in edge until w - edge) {
            if (x < strip || y < strip || x >= w - strip || y >= h - strip) samples += intArrayOf(at(x, y, 0), at(x, y, 1), at(x, y, 2))
        }
        if (samples.size < 50) return null
        val colour = IntArray(3) { c -> samples.map { it[c] }.sorted()[samples.size / 2] }
        fun dist(l: Int, a: Int, b: Int): Double {
            val dl = l - colour[0]; val da = a - colour[1]; val db = b - colour[2]
            return sqrt((dl * dl + da * da + db * db).toDouble())
        }
        // How much the border itself varies (paper grain, noise): the tolerance follows it.
        val spread = samples.map { dist(it[0], it[1], it[2]) }.sorted()
        val tolerance = max(MIN_TOLERANCE, 3.0 * spread[spread.size / 2])
        // The strip has to be mostly that colour, or it's picture, not border.
        if (spread.count { it <= tolerance } < samples.size * 0.85) return null

        /** Share of a row (or column) that's border-coloured, ignoring its ends (corners, the other borders). */
        fun share(index: Int, row: Boolean): Double {
            val n = if (row) w else h
            val from = (n * 0.08).toInt(); val to = n - from
            var hit = 0
            for (i in from until to) {
                val x = if (row) i else index; val y = if (row) index else i
                if (dist(at(x, y, 0), at(x, y, 1), at(x, y, 2)) <= tolerance) hit++
            }
            return hit.toDouble() / (to - from)
        }

        var writing = false
        /** Rows (columns) of border in from one side: stops where the picture starts. */
        fun depth(side: Int): Int {
            val row = side % 2 == 0
            val n = if (row) h else w
            var last = edge - 1
            var misses = 0
            var marked = 0
            // Rows with something on them: border only if more plain border comes after.
            var pending = 0
            for (k in edge until (n * MAX_DEPTH).toInt()) {
                val index = if (side == 0 || side == 3) k else n - 1 - k
                val f = share(index, row)
                when {
                    f >= BORDER_ROW -> {
                        last = k; misses = 0
                        if (f < CLEAN_ROW) marked++
                        marked += pending; pending = 0
                    }
                    f >= WRITTEN_ROW && pending < n * MAX_WRITING -> pending++
                    else -> if (++misses >= GAP) break
                }
            }
            if (marked >= max(3, (last - edge) / 8)) writing = true
            return last + 1
        }
        /**
         * A border ends in a straight, sharp line: just inside it the picture starts all along. A pale
         * background in a borderless photo fades into the subject instead.
         */
        fun sharp(side: Int, depth: Int): Boolean {
            val row = side % 2 == 0
            val n = if (row) h else w
            val k = depth + 2
            if (k >= n) return false
            val index = if (side == 0 || side == 3) k else n - 1 - k
            return share(index, row) <= EDGE_DROP
        }
        val top = depth(0); val right = depth(1); val bottom = depth(2); val left = depth(3)
        if (!(sharp(0, top) && sharp(1, right) && sharp(2, bottom) && sharp(3, left))) return null
        val inset = Inset(left.toFloat() / w, top.toFloat() / h, right.toFloat() / w, bottom.toFloat() / h, writing)
        // A border runs all the way round; a pale sky along one side isn't one.
        if (listOf(inset.left, inset.right).any { it < MIN_DEPTH } || listOf(inset.top, inset.bottom).any { it < MIN_DEPTH }) return null
        if ((1 - inset.left - inset.right) * (1 - inset.top - inset.bottom) < 0.35f) return null
        // A hair more, so no line of the border's edge is left.
        val extra = 0.003f
        return inset.copy(left = inset.left + extra, top = inset.top + extra, right = inset.right + extra, bottom = inset.bottom + extra)
    }

    /**
     * The outline of the picture inside the border, in the same space as [quad] (the print's outline that
     * flattened to [w] x [h]).
     */
    fun inner(quad: Quad, inset: Inset): Quad {
        // Bilinear in the quad is close enough for the narrow border, and exact for a flat-on shot;
        // perspective is handled by mapping through the quad's own homography.
        val h = homography(quad)
        fun map(u: Float, v: Float): Pt {
            val d = h[6] * u + h[7] * v + h[8]
            return Pt(((h[0] * u + h[1] * v + h[2]) / d).toFloat(), ((h[3] * u + h[4] * v + h[5]) / d).toFloat())
        }
        val l = inset.left; val t = inset.top; val r = 1 - inset.right; val b = 1 - inset.bottom
        return Quad(map(l, t), map(r, t), map(r, b), map(l, b))
    }

    /** The unit square onto [quad]. */
    private fun homography(quad: Quad): DoubleArray {
        val from = org.opencv.core.MatOfPoint2f(
            org.opencv.core.Point(0.0, 0.0), org.opencv.core.Point(1.0, 0.0),
            org.opencv.core.Point(1.0, 1.0), org.opencv.core.Point(0.0, 1.0),
        )
        val to = org.opencv.core.MatOfPoint2f(*quad.points.map { org.opencv.core.Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = DoubleArray(9).also { m.get(0, 0, it) }
        listOf(from, to, m).forEach { it.release() }
        return out
    }

    private const val WORK = 800.0
    private const val MIN_TOLERANCE = 9.0
    /** A row this much border-coloured is border; this much, border with writing on it. */
    private const val BORDER_ROW = 0.93
    private const val CLEAN_ROW = 0.985
    private const val WRITTEN_ROW = 0.45
    /** Picture rows in a row before the border is over. */
    private const val GAP = 4
    /** Deepest band of writing on a border, as a share of the side. */
    private const val MAX_WRITING = 0.1
    private const val MAX_DEPTH = 0.4
    /** Most of the row just inside a border that may still be border-coloured. */
    private const val EDGE_DROP = 0.6
    /** Thinnest border that counts, as a share of the side. */
    private const val MIN_DEPTH = 0.012f
}
