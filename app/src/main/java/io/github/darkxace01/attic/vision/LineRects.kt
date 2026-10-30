package io.github.darkxace01.attic.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds rectangles out of straight edge pieces.
 *
 * On a real album page a print's outline is rarely one closed contour: glare on the sleeve wipes out part
 * of an edge, a grey sky meets a grey page, a corner hides under a mount. But its four sides are still
 * long straight lines, mostly there. So: gather edge segments, join the ones that lie on the same line,
 * and try every pair of roughly parallel lines against every pair of lines across them. A rectangle is a
 * candidate when each of its sides is backed by real edge along a good part of its length.
 */
object LineRects {
    class Segment(val a: Pt, val b: Pt) {
        val length get() = a.dist(b)
        /** Direction in [0, π). */
        val angle: Double get() = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()).let { if (it < 0) it + PI else it }.let { if (it >= PI) it - PI else it }
    }

    /** A joined line: where it lies and which stretches of it have edge. */
    class EdgeLine(val line: Line, /** Covered stretches along [line] (parameter t, sorted, merged). */ val covered: List<ClosedFloatingPointRange<Float>>) {
        val coveredLength get() = covered.sumOf { (it.endInclusive - it.start).toDouble() }.toFloat()

        /** Share of [from]..[to] (parameters along the line) that has edge. */
        fun coverage(from: Float, to: Float): Float {
            val lo = min(from, to)
            val hi = max(from, to)
            if (hi - lo < 1f) return 0f
            var c = 0f
            for (r in covered) c += max(0f, min(hi, r.endInclusive) - max(lo, r.start))
            return c / (hi - lo)
        }

        fun param(p: Pt) = (p.x - line.p.x) * line.d.x + (p.y - line.p.y) * line.d.y
    }

    class Found(val quad: Quad, /** Mean side coverage, 0..1. */ val support: Float)

    /**
     * @param minSide, maxSide limits on a rectangle's side length, in pixels.
     * @param tilt how far (degrees) lines may stray from the page's two main directions (perspective).
     */
    fun find(
        segments: List<Segment>,
        minSide: Float,
        maxSide: Float,
        tilt: Double = 14.0,
        minSideCoverage: Float = 0.3f,
        minMeanCoverage: Float = 0.55f,
        maxResults: Int = 60,
    ): List<Found> {
        if (segments.size < 4) return emptyList()
        val main = dominantAngle(segments)
        val tiltRad = Math.toRadians(tilt)
        val familyA = segments.filter { angleDiff(it.angle, main) <= tiltRad }
        val familyB = segments.filter { angleDiff(it.angle, main + PI / 2) <= tiltRad }
        val linesA = join(familyA).filter { it.coveredLength >= minSide * 0.25f }.sortedByDescending { it.coveredLength }.take(50)
        val linesB = join(familyB).filter { it.coveredLength >= minSide * 0.25f }.sortedByDescending { it.coveredLength }.take(50)

        val out = mutableListOf<Found>()
        for (i in linesA.indices) for (j in i + 1 until linesA.size) {
            val a1 = linesA[i]
            val a2 = linesA[j]
            if (!roughlyParallel(a1.line, a2.line)) continue
            val gap = a2.line.distance(a1.line.p)
            if (gap < minSide || gap > maxSide) continue
            // Lines across that have edge between these two.
            val across = linesB.mapNotNull { b ->
                val p1 = b.line.intersect(a1.line) ?: return@mapNotNull null
                val p2 = b.line.intersect(a2.line) ?: return@mapNotNull null
                val cov = b.coverage(b.param(p1), b.param(p2))
                if (cov >= minSideCoverage) Triple(b, p1, p2) else null
            }
            for (k in across.indices) for (l in k + 1 until across.size) {
                val (b1, p11, p12) = across[k]
                val (b2, p21, p22) = across[l]
                if (!roughlyParallel(b1.line, b2.line)) continue
                val width = p11.dist(p21)
                if (width < minSide || width > maxSide) continue
                val covA1 = a1.coverage(a1.param(p11), a1.param(p21))
                if (covA1 < minSideCoverage) continue
                val covA2 = a2.coverage(a2.param(p12), a2.param(p22))
                if (covA2 < minSideCoverage) continue
                val covB1 = b1.coverage(b1.param(p11), b1.param(p12))
                val covB2 = b2.coverage(b2.param(p21), b2.param(p22))
                val mean = (covA1 + covA2 + covB1 + covB2) / 4f
                if (mean < minMeanCoverage) continue
                // Real corners: both edges reach the corner, and at least one of them stops there. Lines
                // that merely cross (a page edge running past the end of a print's edge) don't make one.
                val corners = listOf(
                    Corner(a1, b1, p11, p21, p12), Corner(a1, b2, p21, p11, p22),
                    Corner(a2, b1, p12, p22, p11), Corner(a2, b2, p22, p12, p21),
                )
                if (corners.count { it.formed } < 3 || corners.count { it.ends } < 3) continue
                val quad = Quad.fromCorners(listOf(p11, p21, p22, p12))
                if (!quad.isReasonable(65f, 115f)) continue
                out += Found(quad, mean)
            }
        }
        return out.sortedByDescending { it.support }.take(maxResults)
    }

    /**
     * True when a straight edge runs across the inside of [q], parallel to a pair of its sides, well away
     * from both and along most of their length: the gap between two prints, or a page edge. A print's
     * picture rarely holds a line like that.
     */
    fun crossedInside(q: Quad, lines: List<EdgeLine>): Boolean {
        val pts = q.points
        for (i in 0 until 2) {
            // Sides i and i+2 face each other; the edge we look for runs along them.
            val a = pts[i]; val b = pts[i + 1]
            val c = pts[i + 2]; val d = pts[(i + 3) % 4]
            val len = (a.dist(b) + c.dist(d)) / 2f
            val dir = (b - a) * (1f / a.dist(b))
            val sideA = Line(a, dir)
            val sideB = Line(d, (c - d) * (1f / c.dist(d)))
            val span = sideA.distance(c)
            if (span < 4f) continue
            for (l in lines) {
                if (abs(l.line.d.x * dir.y - l.line.d.y * dir.x) > 0.1f) continue
                var inside = 0f
                for (r in l.covered) {
                    var t = r.start
                    while (t <= r.endInclusive) {
                        val p = l.line.p + l.line.d * t
                        if (q.contains(p) && sideA.distance(p) >= span * 0.15f && sideB.distance(p) >= span * 0.15f) inside += 2f
                        t += 2f
                    }
                }
                if (inside >= len * 0.6f) return true
            }
        }
        return false
    }

    /** Where lines [a] and [b] meet at [p]; [alongA] and [alongB] are the next corners along each. */
    private class Corner(a: EdgeLine, b: EdgeLine, p: Pt, alongA: Pt, alongB: Pt) {
        val formed: Boolean
        val ends: Boolean

        init {
            val (nearA, beyondA) = reach(a, p, alongA)
            val (nearB, beyondB) = reach(b, p, alongB)
            formed = nearA >= 0.4f && nearB >= 0.4f
            ends = beyondA < 0.5f || beyondB < 0.5f
        }

        /** Edge coverage just inside the corner along [l] (towards [next]) and just past it. */
        private fun reach(l: EdgeLine, p: Pt, next: Pt): Pair<Float, Float> {
            val t0 = l.param(p)
            val t1 = l.param(next)
            val len = abs(t1 - t0)
            val dir = if (t1 > t0) 1f else -1f
            return l.coverage(t0, t0 + dir * len * 0.12f) to l.coverage(t0 - dir * len * 0.08f, t0)
        }
    }

    /** The page's main direction (mod 90°), from where most edge length points. */
    fun dominantAngle(segments: List<Segment>): Double {
        val bins = 90
        val hist = DoubleArray(bins)
        for (s in segments) {
            val a = s.angle % (PI / 2)
            hist[((a / (PI / 2)) * bins).toInt().coerceIn(0, bins - 1)] += s.length.toDouble()
        }
        // Smooth circularly, then take the peak.
        val smooth = DoubleArray(bins) { i -> (-2..2).sumOf { hist[(i + it + bins) % bins] } }
        val peak = smooth.indices.maxBy { smooth[it] }
        return (peak + 0.5) / bins * (PI / 2)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = abs(a - b) % PI
        if (d > PI / 2) d = PI - d
        return d
    }

    private fun roughlyParallel(a: Line, b: Line): Boolean = abs(a.d.x * b.d.y - a.d.y * b.d.x) <= sin(Math.toRadians(10.0))

    /**
     * Joins segments lying on the same line (close in angle and in offset) and records which stretches
     * they cover.
     */
    fun join(segments: List<Segment>, maxOffset: Float = 3f, maxAngle: Double = Math.toRadians(3.0)): List<EdgeLine> {
        val sorted = segments.sortedByDescending { it.length }
        val groups = mutableListOf<MutableList<Segment>>()
        val anchors = mutableListOf<Line>()
        for (s in sorted) {
            val mid = (s.a + s.b) * 0.5f
            val idx = anchors.indices.firstOrNull { i ->
                val l = anchors[i]
                val la = atan2(l.d.y.toDouble(), l.d.x.toDouble()).let { if (it < 0) it + PI else it }
                angleDiff(la, s.angle) <= maxAngle && l.distance(mid) <= maxOffset && l.distance(s.a) <= maxOffset * 1.5f && l.distance(s.b) <= maxOffset * 1.5f
            }
            if (idx == null) {
                groups += mutableListOf(s)
                val d = (s.b - s.a) * (1f / s.length)
                anchors += Line(s.a, d)
            } else {
                groups[idx] += s
            }
        }
        return groups.map { g ->
            // Length-weighted fit through the endpoints and midpoints.
            val pts = g.flatMap { s ->
                val n = max(2, (s.length / 4f).toInt())
                (0..n).map { k -> s.a + (s.b - s.a) * (k.toFloat() / n) }
            }
            val line = EdgeFit.robustLine(pts) ?: Line(g[0].a, (g[0].b - g[0].a) * (1f / g[0].length))
            val el = EdgeLine(line, emptyList())
            val ranges = g.map { s ->
                val t1 = el.param(s.a)
                val t2 = el.param(s.b)
                min(t1, t2) - 1.5f..max(t1, t2) + 1.5f
            }.sortedBy { it.start }
            val merged = mutableListOf<ClosedFloatingPointRange<Float>>()
            for (r in ranges) {
                val last = merged.lastOrNull()
                if (last != null && r.start <= last.endInclusive) merged[merged.lastIndex] = last.start..max(last.endInclusive, r.endInclusive)
                else merged += r
            }
            EdgeLine(line, merged)
        }
    }
}
