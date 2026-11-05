package io.github.darkxace01.attic.vision

import kotlin.math.hypot

/** Fixing a print's outline by hand: the handles to grab, and what dragging one does. */
object OutlineEdit {
    /** Corners 0..3, then the middle of each side 4..7 (side k runs from corner k to corner k + 1). */
    fun handles(q: Quad): List<Pt> {
        val p = q.points
        return p + (0 until 4).map { k -> (p[k] + p[(k + 1) % 4]) * 0.5f }
    }

    /**
     * [q] with handle [h] dragged by [d], kept inside a [w] x [hgt] image. A corner follows the finger; a
     * side moves straight in or out and keeps its angle, so sliding along it changes nothing. A drag that
     * would fold the outline over itself or shrink it to nothing is refused.
     */
    fun drag(q: Quad, h: Int, d: Pt, w: Float, hgt: Float): Quad {
        val pts = q.points.toMutableList()
        val clamp = { p: Pt -> Pt(p.x.coerceIn(0f, w), p.y.coerceIn(0f, hgt)) }
        if (h < 4) {
            pts[h] = clamp(pts[h] + d)
        } else {
            val a = h - 4
            val b = (a + 1) % 4
            val sx = pts[b].x - pts[a].x
            val sy = pts[b].y - pts[a].y
            val len = hypot(sx, sy).coerceAtLeast(1f)
            val nx = -sy / len
            val ny = sx / len
            val along = d.x * nx + d.y * ny
            val step = Pt(nx * along, ny * along)
            pts[a] = clamp(pts[a] + step)
            pts[b] = clamp(pts[b] + step)
        }
        val out = Quad(pts[0], pts[1], pts[2], pts[3])
        return if (out.isReasonable(minDeg = 20f, maxDeg = 160f) && out.area > 400f) out else q
    }
}
