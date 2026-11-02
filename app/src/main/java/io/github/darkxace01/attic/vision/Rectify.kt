package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/** Flattens a print and measures how sharp it is. */
object Rectify {
    /**
     * Warps [quad] (pixels of [src]) to a flat, upright rectangle at full resolution, or exactly [size]
     * (width to height) when given, so shots of the same print come out the same size.
     */
    fun warp(src: Mat, quad: Quad, maxSide: Int = Int.MAX_VALUE, size: Pair<Int, Int>? = null): Mat {
        var w = quad.outputWidth
        var h = quad.outputHeight
        val fit = if (size != null) max(size.first / w, size.second / h) else maxSide / max(w, h)
        if (size == null && fit < 1f) { w *= fit; h *= fit }
        val outW = size?.first ?: w.roundToInt().coerceAtLeast(1)
        val outH = size?.second ?: h.roundToInt().coerceAtLeast(1)

        val from = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(outW.toDouble(), 0.0),
            Point(outW.toDouble(), outH.toDouble()), Point(0.0, outH.toDouble()),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        val interp = if (fit < 1f) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC
        Imgproc.warpPerspective(src, out, m, Size(outW.toDouble(), outH.toDouble()), interp, Core.BORDER_REPLICATE)
        listOf(from, to, m).forEach { it.release() }
        return out
    }

    /**
     * Sharpness as the variance of the Laplacian, measured at a fixed size so prints of different
     * resolutions compare fairly. Higher is sharper; under ~60 usually looks soft.
     */
    fun sharpness(rgba: Mat): Double {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val scale = 1000.0 / max(gray.cols(), gray.rows())
        if (scale < 1.0) Imgproc.resize(gray, gray, Size(), scale, scale, Imgproc.INTER_AREA)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val sd = std.toArray()[0]
        listOf(gray, lap, mean, std).forEach { it.release() }
        return sd * sd
    }
}
