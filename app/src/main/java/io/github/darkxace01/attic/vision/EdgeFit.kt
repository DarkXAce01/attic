package io.github.darkxace01.attic.vision

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A straight line through [p] going in unit direction [d]. */
data class Line(val p: Pt, val d: Pt) {
    fun distance(q: Pt): Float = abs((q.x - p.x) * d.y - (q.y - p.y) * d.x)

    fun intersect(o: Line): Pt? {
        val det = d.x * o.d.y - d.y * o.d.x
        if (abs(det) < 1e-6f) return null
        val t = ((o.p.x - p.x) * o.d.y - (o.p.y - p.y) * o.d.x) / det
        return p + d * t
    }
}

object EdgeFit {
    /**
     * Total-least-squares line through [points], then refit without points far from it, so a few
     * samples caught on a scratch or a corner mount don't bend the edge.
     */
    fun robustLine(points: List<Pt>): Line? {
        var pts = points
        var line = fit(pts) ?: return null
        repeat(2) {
            val dists = pts.map(line::distance).sorted()
            val cutoff = maxOf(1.0f, dists[dists.size / 2] * 2.5f)
            val kept = pts.filter { line.distance(it) <= cutoff }
            if (kept.size < 5) return line
            pts = kept
            line = fit(pts) ?: return line
        }
        return line
    }

    private fun fit(pts: List<Pt>): Line? {
        if (pts.size < 2) return null
        val mx = pts.sumOf { it.x.toDouble() } / pts.size
        val my = pts.sumOf { it.y.toDouble() } / pts.size
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in pts) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        // Direction of largest spread.
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(Pt(mx.toFloat(), my.toFloat()), Pt(cos(angle).toFloat(), sin(angle).toFloat()))
    }

    /**
     * Moves each side of [quad] onto the strongest edge nearby, then rebuilds the corners where the
     * fitted sides cross. [gradientAt] returns the image gradient (gx, gy) at a pixel, or null outside
     * the image. Sides that can't be fitted keep their original corners.
     */
    fun snap(quad: Quad, searchPx: Float, gradientAt: (Int, Int) -> Pair<Float, Float>?): Quad {
        val corners = quad.points
        val lines = (0 until 4).map { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val len = a.dist(b)
            if (len < 8f) return quad
            val d = (b - a) * (1f / len)
            val n = Pt(-d.y, d.x)
            val samples = 48
            val found = mutableListOf<Pt>()
            for (k in 0 until samples) {
                // Stay away from the corners, where two edges meet.
                val t = 0.08f + 0.84f * k / (samples - 1)
                val base = a + (b - a) * t
                var best = 0f
                var bestS = 0
                val strengths = FloatArray(searchPx.toInt() * 2 + 1)
                for (j in strengths.indices) {
                    val s = j - searchPx.toInt()
                    val q = base + n * s.toFloat()
                    val g = gradientAt(Math.round(q.x), Math.round(q.y)) ?: continue
                    val v = abs(g.first * n.x + g.second * n.y)
                    strengths[j] = v
                    if (v > best) { best = v; bestS = j }
                }
                if (best <= 0f) continue
                // Parabola through the peak and its neighbours for a sub-pixel position.
                var offset = 0f
                if (bestS in 1 until strengths.size - 1) {
                    val l = strengths[bestS - 1]
                    val r = strengths[bestS + 1]
                    val denom = l - 2 * best + r
                    if (denom < 0f) offset = (0.5f * (l - r) / denom).coerceIn(-0.5f, 0.5f)
                }
                found += base + n * (bestS - searchPx.toInt() + offset)
            }
            if (found.size < samples / 3) null else robustLine(found)
        }

        val out = (0 until 4).map { i ->
            val prev = lines[(i + 3) % 4]
            val cur = lines[i]
            val c = if (prev != null && cur != null) prev.intersect(cur) else null
            if (c == null || c.dist(corners[i]) > searchPx * 2f) corners[i] else c
        }
        return Quad(out[0], out[1], out[2], out[3])
    }

    fun diagonal(q: Quad) = hypot(q.outputWidth, q.outputHeight)
}
