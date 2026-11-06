package io.github.darkxace01.attic.vision

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Then and now: a photo taken today from where an old one was, turned and stretched to sit exactly over
 * the old print. Matching works on what hasn't changed (a doorway, a roofline, the hills); when too much
 * has changed to find enough of it, the framing the person lined up by eye is kept.
 */
object Align {
    /** [image] is today's photo in the old print's frame (old's shape); [matched] if it was lined up by its contents. */
    class Result(
        val image: Mat,
        val matched: Boolean,
        /** Where the old print's corners fell in today's photo (x, y in its pixels), clockwise from top left. */
        val corners: List<Pair<Double, Double>>,
    )

    /** Long side the matching works at. */
    private const val WORK = 900
    private const val MIN_AGREE = 25
    private const val MIN_SHARE = 0.25

    /**
     * Today's photo [now] (RGBA) over [old] (RGBA). [guess] is where the old print sat over the camera
     * view when it was taken, in [now]'s pixels: the fallback, and the check that a match is believable.
     * The caller releases the result's image.
     */
    fun toOld(old: Mat, now: Mat, guess: Rect): Result {
        val outW = guess.width
        val outH = max(1, (guess.width.toDouble() * old.rows() / old.cols()).toInt())
        val h = homography(old, now, guess)
        if (h != null) {
            // h maps now's work pixels to old's work pixels; the output is old's frame at outW x outH.
            val so = min(1.0, WORK.toDouble() / max(old.cols(), old.rows()))
            val sn = min(1.0, WORK.toDouble() / max(now.cols(), now.rows()))
            val k = outW / (old.cols() * so)
            val full = mul(mul(diag(k), h), diag(sn))
            val m = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, *full) }
            val out = Mat()
            Imgproc.warpPerspective(now, out, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            val inv = invert(full)!!
            val corners = listOf(0.0 to 0.0, outW.toDouble() to 0.0, outW.toDouble() to outH.toDouble(), 0.0 to outH.toDouble()).map { (x, y) ->
                val z = inv[6] * x + inv[7] * y + inv[8]
                ((inv[0] * x + inv[1] * y + inv[2]) / z) to ((inv[3] * x + inv[4] * y + inv[5]) / z)
            }
            m.release()
            return Result(out, true, corners)
        }
        val r = Rect(guess.x.coerceIn(0, now.cols() - 1), guess.y.coerceIn(0, now.rows() - 1), 0, 0)
        r.width = min(guess.width, now.cols() - r.x); r.height = min(guess.height, now.rows() - r.y)
        val out = Mat()
        Imgproc.resize(now.submat(r), out, Size(outW.toDouble(), outH.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val c = listOf(r.x to r.y, r.x + r.width to r.y, r.x + r.width to r.y + r.height, r.x to r.y + r.height).map { (x, y) -> x.toDouble() to y.toDouble() }
        return Result(out, false, c)
    }

    /** now (work pixels) → old (work pixels), or null when there's no believable match. */
    private fun homography(old: Mat, now: Mat, guess: Rect): DoubleArray? {
        val so = min(1.0, WORK.toDouble() / max(old.cols(), old.rows()))
        val sn = min(1.0, WORK.toDouble() / max(now.cols(), now.rows()))
        val a = prepared(old, so)
        val b = prepared(now, sn)
        val orb = ORB.create(4000)
        val ka = MatOfKeyPoint(); val kb = MatOfKeyPoint()
        val da = Mat(); val db = Mat()
        orb.detectAndCompute(a, Mat(), ka, da)
        orb.detectAndCompute(b, Mat(), kb, db)
        var fit: DoubleArray? = null
        if (!da.empty() && !db.empty()) {
            // Ratio test: keep a match only when it's clearly better than the next best.
            val knn = ArrayList<MatOfDMatch>()
            BFMatcher.create(Core.NORM_HAMMING, false).knnMatch(db, da, knn, 2)
            val good = knn.mapNotNull { m -> m.toArray().takeIf { it.size == 2 && it[0].distance < 0.8f * it[1].distance }?.get(0) }
            knn.forEach { it.release() }
            val pa = ka.toArray(); val pb = kb.toArray()
            if (good.size >= MIN_AGREE) {
                val src = MatOfPoint2f(*good.map { pb[it.queryIdx].pt }.toTypedArray())
                val dst = MatOfPoint2f(*good.map { pa[it.trainIdx].pt }.toTypedArray())
                val inliers = Mat()
                val hm = Calib3d.findHomography(src, dst, Calib3d.RANSAC, 4.0, inliers, 3000, 0.995)
                val agree = if (hm.empty()) 0 else Core.countNonZero(inliers)
                if (agree >= MIN_AGREE && agree >= MIN_SHARE * good.size) {
                    val h = DoubleArray(9) { hm.get(it / 3, it % 3)[0] }
                    val g = doubleArrayOf(guess.x * sn, guess.y * sn, guess.width * sn, guess.height * sn)
                    if (plausible(h, a.cols().toDouble(), a.rows().toDouble(), g)) fit = h
                }
                listOf(src, dst, inliers, hm).forEach { it.release() }
            }
        }
        listOf(a, b, ka, kb, da, db).forEach { it.release() }
        return fit
    }

    /** Grey, small, and evened out: an old print is faded and often black and white, today's isn't. */
    private fun prepared(m: Mat, s: Double): Mat {
        val small = Mat()
        Imgproc.resize(m, small, Size(), s, s, Imgproc.INTER_AREA)
        val g = Mat()
        Imgproc.cvtColor(small, g, if (m.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        small.release()
        Imgproc.createCLAHE(2.5, Size(8.0, 8.0)).apply(g, g)
        return g
    }

    /**
     * Whether a fit (now → old) makes sense: the old frame, carried back into today's photo, is a convex
     * quad roughly where the person held it ([guess]: x, y, w, h), about the same size, not wildly skewed.
     */
    fun plausible(h: DoubleArray, oldW: Double, oldH: Double, guess: DoubleArray): Boolean {
        val inv = invert(h) ?: return false
        val corners = listOf(0.0 to 0.0, oldW to 0.0, oldW to oldH, 0.0 to oldH).map { (x, y) ->
            val z = inv[6] * x + inv[7] * y + inv[8]
            if (z <= 0) return false
            ((inv[0] * x + inv[1] * y + inv[2]) / z) to ((inv[3] * x + inv[4] * y + inv[5]) / z)
        }
        // Convex, same winding all round.
        val cross = (0 until 4).map { i ->
            val (ax, ay) = corners[i]; val (bx, by) = corners[(i + 1) % 4]; val (cx, cy) = corners[(i + 2) % 4]
            (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        }
        if (!(cross.all { it > 0 } || cross.all { it < 0 })) return false
        val area = abs((0 until 4).sumOf { i -> corners[i].first * corners[(i + 1) % 4].second - corners[(i + 1) % 4].first * corners[i].second }) / 2
        val guessArea = guess[2] * guess[3]
        if (area < 0.3 * guessArea || area > 3.0 * guessArea) return false
        // Opposite sides within 2.5x of each other: a real change of viewpoint, not a collapse.
        fun len(i: Int) = kotlin.math.hypot(corners[(i + 1) % 4].first - corners[i].first, corners[(i + 1) % 4].second - corners[i].second)
        if (max(len(0), len(2)) > 2.5 * min(len(0), len(2)) || max(len(1), len(3)) > 2.5 * min(len(1), len(3))) return false
        // Its middle no further from the guess's middle than the guess is wide.
        val mx = corners.sumOf { it.first } / 4; val my = corners.sumOf { it.second } / 4
        return kotlin.math.hypot(mx - (guess[0] + guess[2] / 2), my - (guess[1] + guess[3] / 2)) < max(guess[2], guess[3])
    }

    private fun diag(k: Double) = doubleArrayOf(k, 0.0, 0.0, 0.0, k, 0.0, 0.0, 0.0, 1.0)

    private fun mul(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun invert(m: DoubleArray): DoubleArray? {
        val (a, b, c) = Triple(m[0], m[1], m[2]); val (d, e, f) = Triple(m[3], m[4], m[5]); val (g, h, i) = Triple(m[6], m[7], m[8])
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-12) return null
        return doubleArrayOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )
    }
}
