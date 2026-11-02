package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.calib3d.Calib3d
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.core.TermCriteria
import org.opencv.video.Video
import kotlin.math.max
import kotlin.math.min

/**
 * Glare-free prints from several shots taken from slightly different angles. A reflection sits in a
 * different place on the print in each shot, so wherever the main shot has glare, the same spot is taken
 * from the shot where it's darkest. Everywhere else the main shot is kept as it is, so nothing gets soft.
 *
 * Every input is a print already flattened to the same size; [align] lines them up to the pixel.
 */
object Deglare {
    /** Work size for alignment and glare maps: plenty for where glare is, and quick. */
    private const val WORK = 720.0

    /**
     * [other] warped onto [base] (both RGBA, same size) with a perspective fit, or null if they don't line
     * up: a different print, or too blurred to match.
     */
    fun align(base: Mat, other: Mat): Mat? {
        val s = min(1.0, WORK / max(base.cols(), base.rows()))
        val a = smallGray(base, s)
        val b = smallGray(other, s)
        // Glare would pull the fit towards itself: leave out anything near white in either shot.
        val mask = Mat()
        val ma = Mat()
        val mb = Mat()
        Imgproc.threshold(a, ma, 225.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.threshold(b, mb, 225.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Core.bitwise_and(ma, mb, mask)
        listOf(ma, mb).forEach { it.release() }
        // Distinct points (corners in the picture) matched between the shots, and a perspective fit
        // through the matches that agree, so a few wrong matches can't pull it off.
        val orb = ORB.create(1500)
        val ka = MatOfKeyPoint(); val kb = MatOfKeyPoint()
        val da = Mat(); val db = Mat()
        orb.detectAndCompute(a, mask, ka, da)
        orb.detectAndCompute(b, mask, kb, db)
        var fit: DoubleArray? = null
        if (!da.empty() && !db.empty()) {
            val matches = MatOfDMatch()
            BFMatcher.create(Core.NORM_HAMMING, true).match(da, db, matches)
            val good = matches.toArray().sortedBy { it.distance }.take(600)
            val pa = ka.toArray(); val pb = kb.toArray()
            if (good.size >= 12) {
                val src = MatOfPoint2f(*good.map { pa[it.queryIdx].pt }.toTypedArray())
                val dst = MatOfPoint2f(*good.map { pb[it.trainIdx].pt }.toTypedArray())
                val inliers = Mat()
                val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 2.0, inliers, 2000, 0.995)
                // Most good matches agreeing on one fit means it's the same print; a different picture
                // gives a handful of chance matches.
                val agree = if (h.empty()) 0 else Core.countNonZero(inliers)
                if (agree >= 20 && agree >= 0.3 * good.size) fit = DoubleArray(9) { h.get(it / 3, it % 3)[0] }
                listOf(src, dst, inliers, h).forEach { it.release() }
            }
            matches.release()
        }
        listOf(ka, kb, da, db).forEach { it.release() }
        // Then the last fraction of a pixel, and a check that it really is the same print: a shift fitted
        // on top, with how well the two then correlate. A plain print (sky, a wall) has too few distinct
        // points for the fit above, but the outlines already put the shots within a few pixels, so the
        // shift alone lines it up. A shift can't run away the way a full fit can on a smooth picture.
        // Both starting points get the shift: the fit from matched points, and no fit at all (on a plain
        // print a few loose matches can make the fit worse than none). The better match wins.
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val best = listOfNotNull(fit, identity).map { shiftOnTop(a, b, mask, it) }.maxBy { it.second }
        listOf(a, b, mask).forEach { it.release() }
        if (best.second < MIN_CORRELATION) return null
        val w0 = best.first
        val w = scaled(w0, 1.0 / s)
        val full = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, *w) }
        // The flats were cut from outlines already good to a few pixels; a big move means a bad match.
        val limit = 0.04 * max(base.cols(), base.rows())
        val corners = listOf(0.0 to 0.0, base.cols() - 1.0 to 0.0, base.cols() - 1.0 to base.rows() - 1.0, 0.0 to base.rows() - 1.0)
        val moved = corners.maxOf { (x, y) ->
            val z = w[6] * x + w[7] * y + w[8]
            val u = (w[0] * x + w[1] * y + w[2]) / z
            val v = (w[3] * x + w[4] * y + w[5]) / z
            kotlin.math.hypot(u - x, v - y)
        }
        if (moved > limit) { full.release(); return null }
        val out = Mat()
        Imgproc.warpPerspective(
            other, out, full, base.size(),
            Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, org.opencv.core.Core.BORDER_REPLICATE,
        )
        full.release()
        return out
    }

    /**
     * [base] with its glare replaced from [others] (all RGBA, same size as [base], not yet aligned). The
     * caller releases the result. Shots that don't line up are left out.
     */
    fun merge(base: Mat, others: List<Mat>): Mat {
        val aligned = others.mapNotNull { align(base, it) }
        if (aligned.isEmpty()) return base.clone()
        val s = min(1.0, WORK / max(base.cols(), base.rows()))
        val lb = smallGray(base, s).also { it.convertTo(it, CvType.CV_32F) }
        // Auto exposure differs between shots: match each one's brightness to the main shot, measured
        // where neither has glare.
        val gains = DoubleArray(aligned.size)
        val lks = aligned.mapIndexed { k, m ->
            val l = smallGray(m, s).also { it.convertTo(it, CvType.CV_32F) }
            val valid = Mat()
            val t1 = Mat(); val t2 = Mat()
            Imgproc.threshold(lb, t1, 220.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.threshold(l, t2, 220.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(t1, t2, valid)
            valid.convertTo(valid, CvType.CV_8U)
            val mb = Core.mean(lb, valid).`val`[0]
            val ml = Core.mean(l, valid).`val`[0]
            listOf(valid, t1, t2).forEach { it.release() }
            gains[k] = if (ml > 1.0) (mb / ml).coerceIn(0.6, 1.6) else 1.0
            Core.multiply(l, Scalar(gains[k]), l)
            l
        }
        // The darkest of the other shots at every spot.
        val darkest = lks[0].clone()
        for (l in lks.drop(1)) Core.min(darkest, l, darkest)

        // Glare in the main shot: brighter than the darkest other shot by a clear margin. It fades in over
        // a range so there is no hard seam, then spreads a little to cover the halo round a reflection.
        // Against the darkest shot's brightest neighbour, so a pixel of misalignment along a sharp edge
        // in the picture doesn't look like glare.
        val darkestNear = Mat()
        Imgproc.dilate(darkest, darkestNear, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val excess = Mat()
        Core.subtract(lb, darkestNear, excess)
        darkestNear.release()
        val glare = Mat()
        Core.subtract(excess, Scalar(GLARE_FROM), glare)
        Core.multiply(glare, Scalar(1.0 / (GLARE_FULL - GLARE_FROM)), glare)
        Imgproc.threshold(glare, glare, 1.0, 1.0, Imgproc.THRESH_TRUNC)
        Imgproc.threshold(glare, glare, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val r = max(3, (0.02 * max(lb.cols(), lb.rows())).toInt()) or 1
        Imgproc.dilate(glare, glare, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(r.toDouble(), r.toDouble())))
        Imgproc.GaussianBlur(glare, glare, Size(r * 2.0 + 1, r * 2.0 + 1), 0.0)

        // Among the other shots, each spot comes mostly from the darkest one.
        val weights = lks.map { l ->
            val w = Mat()
            Core.subtract(l, darkest, w)
            Core.multiply(w, Scalar(-1.0 / 8.0), w)
            Core.exp(w, w)
            Imgproc.GaussianBlur(w, w, Size(5.0, 5.0), 0.0)
            w
        }
        val total = Mat.zeros(lb.size(), CvType.CV_32F)
        for (w in weights) Core.add(total, w, total)

        // Blend at full size: out = base * (1 - glare) + glare * sum(w_k * gain_k * shot_k) / sum(w_k).
        val size = base.size()
        fun up(m: Mat): Mat = Mat().also { Imgproc.resize(m, it, size, 0.0, 0.0, Imgproc.INTER_LINEAR) }
        val g = up(glare)
        val fill = Mat.zeros(size, CvType.CV_32FC3)
        val totalUp = up(total)
        for ((k, m) in aligned.withIndex()) {
            val wk = up(weights[k])
            Core.divide(wk, totalUp, wk)
            Core.multiply(wk, Scalar(gains[k]), wk)
            val rgb = toRgbF(m)
            val w3 = Mat().also { Imgproc.cvtColor(wk, it, Imgproc.COLOR_GRAY2RGB) }
            Core.multiply(rgb, w3, rgb)
            Core.add(fill, rgb, fill)
            listOf(wk, rgb, w3).forEach { it.release() }
        }
        val baseRgb = toRgbF(base)
        val g3 = Mat().also { Imgproc.cvtColor(g, it, Imgproc.COLOR_GRAY2RGB) }
        val keep = Mat()
        Core.subtract(Mat(size, CvType.CV_32FC3, Scalar(1.0, 1.0, 1.0)), g3, keep)
        Core.multiply(baseRgb, keep, baseRgb)
        Core.multiply(fill, g3, fill)
        Core.add(baseRgb, fill, baseRgb)
        val out8 = Mat()
        baseRgb.convertTo(out8, CvType.CV_8UC3)
        val out = Mat()
        Imgproc.cvtColor(out8, out, Imgproc.COLOR_RGB2RGBA)

        (aligned + lks + weights + listOf(lb, darkest, excess, glare, total, g, fill, totalUp, baseRgb, g3, keep, out8)).forEach { it.release() }
        return out
    }

    /**
     * Share of [rgba] (a flattened print) that looks like glare: blown-out, colourless spots, away from
     * the edges where a white border would count.
     */
    fun glareShare(rgba: Mat): Float {
        val w = rgba.cols()
        val h = rgba.rows()
        val inset = org.opencv.core.Rect((w * 0.1).toInt(), (h * 0.1).toInt(), max(1, (w * 0.8).toInt()), max(1, (h * 0.8).toInt()))
        val inner = rgba.submat(inset)
        val rgb = Mat()
        Imgproc.cvtColor(inner, rgb, Imgproc.COLOR_RGBA2RGB)
        inner.release()
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val spots = Mat()
        Core.inRange(hsv, Scalar(0.0, 0.0, 248.0), Scalar(180.0, 40.0, 255.0), spots)
        val share = Core.countNonZero(spots).toFloat() / (spots.rows() * spots.cols()).coerceAtLeast(1)
        listOf(rgb, hsv, spots).forEach { it.release() }
        return share
    }

    /** Where [q] lands in another shot of the same flat page, given where [from] landed there as [to]. */
    fun mapQuad(q: Quad, from: Quad, to: Quad): Quad {
        fun mat(x: Quad) = MatOfPoint2f(*x.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val a = mat(from)
        val b = mat(to)
        val h = Imgproc.getPerspectiveTransform(a, b)
        val src = mat(q)
        val dst = MatOfPoint2f()
        Core.perspectiveTransform(src, dst, h)
        val p = dst.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) }
        listOf(a, b, h, src, dst).forEach { it.release() }
        return Quad(p[0], p[1], p[2], p[3])
    }

    /** [first] (base -> other) with a shift fitted on top, and how well the shots then correlate. */
    private fun shiftOnTop(a: Mat, b: Mat, mask: Mat, first: DoubleArray): Pair<DoubleArray, Double> {
        val bOnA = Mat()
        val firstMat = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, *first) }
        Imgproc.warpPerspective(b, bOnA, firstMat, a.size(), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
        firstMat.release()
        val shift = Mat.eye(2, 3, CvType.CV_32F)
        val rho = runCatching {
            Video.findTransformECC(a, bOnA, shift, Video.MOTION_TRANSLATION, TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 50, 1e-4), mask, 5)
        }.getOrDefault(0.0)
        val tx = shift.get(0, 2)[0]
        val ty = shift.get(1, 2)[0]
        listOf(bOnA, shift).forEach { it.release() }
        // base -> other: first the shift, then [first].
        return doubleArrayOf(
            first[0], first[1], first[0] * tx + first[1] * ty + first[2],
            first[3], first[4], first[3] * tx + first[4] * ty + first[5],
            first[6], first[7], first[6] * tx + first[7] * ty + first[8],
        ) to rho
    }

    /** A homography between images scaled by [f], from one between the originals: S * W * S^-1. */
    private fun scaled(w: DoubleArray, f: Double) = doubleArrayOf(
        w[0], w[1], w[2] * f,
        w[3], w[4], w[5] * f,
        w[6] / f, w[7] / f, w[8],
    )

    private fun smallGray(rgba: Mat, s: Double): Mat {
        val g = Mat()
        Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
        if (s < 1.0) Imgproc.resize(g, g, Size(), s, s, Imgproc.INTER_AREA)
        return g
    }

    private fun toRgbF(rgba: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgb.convertTo(rgb, CvType.CV_32FC3)
        return rgb
    }

    /** How well two shots of the same print correlate once lined up, at least. */
    private const val MIN_CORRELATION = 0.6

    /** Brighter than the darkest other shot by this much (0..255) starts to count as glare... */
    private const val GLARE_FROM = 5.0

    /** ...and by this much it's replaced entirely. */
    private const val GLARE_FULL = 25.0
}
