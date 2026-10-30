package io.github.darkxace01.attic.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Float) = Pt(x * s, y * s)
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

/**
 * Four corners of a print, ordered top-left, top-right, bottom-right, bottom-left.
 * Coordinates are whatever space the caller works in: pixels, or 0..1 of the frame.
 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    val center: Pt get() = Pt((tl.x + tr.x + br.x + bl.x) / 4f, (tl.y + tr.y + br.y + bl.y) / 4f)

    val area: Float get() = abs(signedArea(points))

    /** Size of the flattened print: the longer of each pair of opposite sides. */
    val outputWidth: Float get() = max(tl.dist(tr), bl.dist(br))
    val outputHeight: Float get() = max(tl.dist(bl), tr.dist(br))

    fun map(f: (Pt) -> Pt) = Quad(f(tl), f(tr), f(br), f(bl))

    fun scale(sx: Float, sy: Float) = map { Pt(it.x * sx, it.y * sy) }

    fun lerp(to: Quad, t: Float) = Quad(
        tl + (to.tl - tl) * t, tr + (to.tr - tr) * t, br + (to.br - br) * t, bl + (to.bl - bl) * t,
    )

    /** Largest distance any corner moved between this quad and [o]. */
    fun maxCornerShift(o: Quad): Float = points.zip(o.points).maxOf { (a, b) -> a.dist(b) }

    fun contains(p: Pt): Boolean = pointInConvex(points, p)

    fun iou(o: Quad): Float {
        val inter = abs(signedArea(clipConvex(points, o.points)))
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** True when every inside angle is within [minDeg]..[maxDeg] and the shape is convex. */
    fun isReasonable(minDeg: Float = 55f, maxDeg: Float = 125f): Boolean {
        val pts = points
        val sign = signedArea(pts) > 0
        for (i in 0 until 4) {
            val prev = pts[(i + 3) % 4]
            val cur = pts[i]
            val next = pts[(i + 1) % 4]
            val a = prev - cur
            val b = next - cur
            val cross = (cur.x - prev.x) * (next.y - cur.y) - (cur.y - prev.y) * (next.x - cur.x)
            if ((cross > 0) != sign) return false
            val la = hypot(a.x, a.y)
            val lb = hypot(b.x, b.y)
            if (la == 0f || lb == 0f) return false
            val cos = ((a.x * b.x + a.y * b.y) / (la * lb)).coerceIn(-1f, 1f)
            val deg = Math.toDegrees(acos(cos).toDouble()).toFloat()
            if (deg < minDeg || deg > maxDeg) return false
        }
        return true
    }

    companion object {
        /**
         * Orders four arbitrary corners. Sorting by angle around the centre keeps them in ring order;
         * the top-left is the corner with the smallest x + y.
         */
        fun fromCorners(corners: List<Pt>): Quad {
            require(corners.size == 4)
            val cx = corners.sumOf { it.x.toDouble() } / 4
            val cy = corners.sumOf { it.y.toDouble() } / 4
            // Screen coordinates (y down): increasing atan2 goes clockwise on screen.
            val ring = corners.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            val start = ring.indices.minBy { ring[it].x + ring[it].y }
            val r = List(4) { ring[(start + it) % 4] }
            return Quad(r[0], r[1], r[2], r[3])
        }

        /** Corners averaged one by one. Both quads must already be in the same order. */
        fun average(a: Quad, b: Quad) = a.lerp(b, 0.5f)
    }
}

internal fun signedArea(poly: List<Pt>): Float {
    var s = 0f
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        s += a.x * b.y - b.x * a.y
    }
    return s / 2f
}

internal fun pointInConvex(poly: List<Pt>, p: Pt): Boolean {
    var sign = 0
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
        if (s != 0) {
            if (sign != 0 && s != sign) return false
            sign = s
        }
    }
    return true
}

/** Sutherland–Hodgman: the part of [subject] inside the convex polygon [clip]. */
internal fun clipConvex(subject: List<Pt>, clip: List<Pt>): List<Pt> {
    val orientation = if (signedArea(clip) >= 0) 1f else -1f
    var output = subject
    for (i in clip.indices) {
        if (output.isEmpty()) break
        val a = clip[i]
        val b = clip[(i + 1) % clip.size]
        fun inside(p: Pt) = orientation * ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) >= 0
        fun intersect(p: Pt, q: Pt): Pt {
            val d1 = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            val d2 = (b.x - a.x) * (q.y - a.y) - (b.y - a.y) * (q.x - a.x)
            val t = d1 / (d1 - d2)
            return p + (q - p) * t
        }
        val input = output
        output = buildList {
            for (j in input.indices) {
                val cur = input[j]
                val prev = input[(j + input.size - 1) % input.size]
                val curIn = inside(cur)
                val prevIn = inside(prev)
                if (curIn) {
                    if (!prevIn) add(intersect(prev, cur))
                    add(cur)
                } else if (prevIn) {
                    add(intersect(prev, cur))
                }
            }
        }
    }
    return output
}

internal fun clamp01(v: Float) = min(1f, max(0f, v))
