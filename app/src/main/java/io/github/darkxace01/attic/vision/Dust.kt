package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min

/**
 * Dust, specks and hairs on a print, painted out. Something counts as dust only when it's small (a speck)
 * or thin (a hair, a scratch), stands out from a median-smoothed copy of the print, and the print right
 * around it is calm: against that calm it has to stand out several times more than the print itself
 * varies there. Detail in a busy area (leaves, fabric, a crowd) is never touched, and nothing inside
 * [protect] is (faces: the light in someone's eye is a speck too). Only what's found is repainted, from
 * its surroundings; the rest of the print is untouched.
 */
object Dust {
    /** The cleaned print and how many specks were painted out. [rgba] is not changed. */
    class Result(val image: Mat, val specks: Int)

    fun clean(rgba: Mat, protect: List<FloatArray> = emptyList()): Result {
        val w = rgba.cols(); val h = rgba.rows()
        // Speck size scales with the print: the median window is about 1.2% of the short side.
        val k = (min(w, h) * 0.012).toInt().let { if (it % 2 == 0) it + 1 else it }.coerceIn(7, 21)
        val lab = Mat()
        Imgproc.cvtColor(rgba, lab, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
        val l = Mat()
        Core.extractChannel(lab, l, 0)
        lab.release()
        val med = Mat()
        Imgproc.medianBlur(l, med, k)
        val diff = Mat()
        Core.absdiff(l, med, diff)
        diff.convertTo(diff, CvType.CV_32F)
        // How much the print itself differs from its smoothed copy around each point, over a wide area:
        // calm sky low, grass and fabric high (the smoothing wipes their fine texture out too). Dust is
        // sparse, so it hardly raises this.
        val busy = Mat()
        Imgproc.GaussianBlur(diff, busy, Size(0.0, 0.0), k * 3.0)
        // A speck has to stand out RATIO times more than that, and by MIN_CONTRAST at least.
        val bar = Mat()
        Core.multiply(busy, org.opencv.core.Scalar(RATIO), bar)
        Core.add(bar, org.opencv.core.Scalar(MARGIN), bar)
        Core.max(bar, org.opencv.core.Scalar(MIN_CONTRAST), bar)
        val cand = Mat()
        Core.compare(diff, bar, cand, Core.CMP_GT)
        listOf(busy, bar, diff, l, med).forEach { it.release() }

        val labels = Mat(); val stats = Mat(); val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(cand, labels, stats, centroids, 8, CvType.CV_32S)
        centroids.release()
        val lab32 = IntArray(w * h).also { labels.get(0, 0, it) }
        listOf(cand, labels).forEach { it.release() }

        val guard = protect.map { r -> intArrayOf((r[0] * w).toInt(), (r[1] * h).toInt(), (r[2] * w).toInt(), (r[3] * h).toInt()) }
        val chosen = BooleanArray(n)
        var specks = 0
        val st = IntArray(5)
        for (i in 1 until n) {
            stats.get(i, 0, st)
            val (x, y, bw, bh, area) = st
            val long = max(bw, bh)
            if (area < MIN_AREA) continue
            val small = area <= k * k * 0.6 && long <= k
            // A hair or a scratch: long and never more than a few pixels wide.
            val thin = long >= k && long <= THIN_MAX * max(w, h) && area.toFloat() / long <= 2.5f
            if (!small && !thin) continue
            if (guard.any { g -> x + bw > g[0] && x < g[2] && y + bh > g[1] && y < g[3] }) continue
            chosen[i] = true
            specks++
        }
        val keep = ByteArray(w * h)
        for (p in keep.indices) if (chosen[lab32[p]]) keep[p] = -1
        stats.release()
        val out = Mat()
        if (specks == 0) {
            rgba.copyTo(out)
            return Result(out, 0)
        }
        val mask = Mat(h, w, CvType.CV_8U)
        mask.put(0, 0, keep)
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val painted = Mat()
        Photo.inpaint(rgb, mask, painted, 3.0, Photo.INPAINT_TELEA)
        Imgproc.cvtColor(painted, out, Imgproc.COLOR_RGB2RGBA)
        listOf(mask, rgb, painted).forEach { it.release() }
        return Result(out, specks)
    }

    /** Smallest difference from the smoothed print (0..255 lightness) worth a look. */
    internal var MIN_CONTRAST = 18.0
    /** How many times more a speck must stand out than the print varies around it. */
    internal var RATIO = 10.0
    /** Smallest speck, in pixels: one or two pixels off is grain or JPEG, not dust. */
    internal var MIN_AREA = 5
    private const val MARGIN = 4.0
    /** Longest hair or scratch, as a share of the print's long side; longer lines are part of the picture. */
    private const val THIN_MAX = 0.25
}
