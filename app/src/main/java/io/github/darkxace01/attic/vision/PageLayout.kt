package io.github.darkxace01.attic.vision

import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Rebuilding an album page from the photo it was scanned from: the part of the photo that is the page
 * (the prints with a margin of page round them), and where each flattened print sits on it.
 */
object PageLayout {
    /** A rectangle in photo pixels. */
    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** A print on the page: centre and size as shares of the frame (0..1), turned [angleDeg] clockwise. */
    data class Spot(val cx: Float, val cy: Float, val w: Float, val h: Float, val angleDeg: Float)

    /**
     * The page around [quads] in a [photoW] x [photoH] photo: their bounding box grown by [margin] of its
     * longer side, kept inside the photo.
     */
    fun frame(quads: List<Quad>, photoW: Float, photoH: Float, margin: Float = 0.07f): Frame {
        val xs = quads.flatMap { q -> q.points.map { it.x } }
        val ys = quads.flatMap { q -> q.points.map { it.y } }
        val l = xs.min(); val r = xs.max(); val t = ys.min(); val b = ys.max()
        val m = max(r - l, b - t) * margin
        return Frame(max(0f, l - m), max(0f, t - m), min(photoW, r + m), min(photoH, b + m))
    }

    /** Where print [q] sits in [frame]: its centre, its size (the mean of opposite sides) and its tilt. */
    fun spot(q: Quad, frame: Frame): Spot {
        val w = (q.tl.dist(q.tr) + q.bl.dist(q.br)) / 2
        val h = (q.tl.dist(q.bl) + q.tr.dist(q.br)) / 2
        // The tilt of the top and bottom edges, averaged.
        val a1 = atan2(q.tr.y - q.tl.y, q.tr.x - q.tl.x)
        val a2 = atan2(q.br.y - q.bl.y, q.br.x - q.bl.x)
        val angle = Math.toDegrees(((a1 + a2) / 2).toDouble()).toFloat()
        val c = q.center
        return Spot((c.x - frame.left) / frame.width, (c.y - frame.top) / frame.height, w / frame.width, h / frame.height, angle)
    }
}
