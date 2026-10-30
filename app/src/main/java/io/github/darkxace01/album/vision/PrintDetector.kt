package io.github.darkxace01.album.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds rectangular prints in an RGBA image. Returns quads in the input image's pixel coordinates.
 *
 * The image is shrunk to [workSize] on its long side, then two edge maps are combined: brightness
 * edges (a print against a dark table) and saturation edges (a colour print against a grey page, where
 * brightness alone can be equal). Closed contours that are close to four-sided become candidates.
 */
class PrintDetector(
    private val workSize: Int = 640,
    /** Smallest print, as a share of the image area. */
    private val minAreaShare: Float = 0.015f,
) {
    fun detect(rgba: Mat): List<Quad> {
        val scale = workSize.toDouble() / max(rgba.cols(), rgba.rows())
        val small = Mat()
        if (scale < 1.0) {
            Imgproc.resize(rgba, small, Size(rgba.cols() * scale, rgba.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        } else {
            rgba.copyTo(small)
        }
        val s = if (scale < 1.0) scale else 1.0

        val edges = edgeMap(small)
        val raw = candidates(edges, small.cols(), small.rows())
        small.release()
        edges.release()

        val inv = (1.0 / s).toFloat()
        return Candidates.resolve(raw).map { it.scale(inv, inv) }
    }

    /**
     * Re-detects one print at higher resolution inside [region] of a full-size image, starting from a
     * rough [guess] in full-image pixels. Returns the refined quad, or the guess when nothing matches.
     */
    fun refine(full: Mat, guess: Quad): Quad {
        val pad = 0.06f
        val minX = guess.points.minOf { it.x }
        val maxX = guess.points.maxOf { it.x }
        val minY = guess.points.minOf { it.y }
        val maxY = guess.points.maxOf { it.y }
        val padX = (maxX - minX) * pad
        val padY = (maxY - minY) * pad
        val x0 = (minX - padX).roundToInt().coerceIn(0, full.cols() - 1)
        val y0 = (minY - padY).roundToInt().coerceIn(0, full.rows() - 1)
        val x1 = (maxX + padX).roundToInt().coerceIn(x0 + 1, full.cols())
        val y1 = (maxY + padY).roundToInt().coerceIn(y0 + 1, full.rows())
        val roi = full.submat(Rect(x0, y0, x1 - x0, y1 - y0))

        val local = PrintDetector(workSize = 1400, minAreaShare = 0.25f).detect(roi)
        roi.release()

        val guessLocal = guess.map { Pt(it.x - x0, it.y - y0) }
        val best = local.maxByOrNull { it.iou(guessLocal) }?.takeIf { it.iou(guessLocal) >= 0.75f }
        val rough = best?.map { Pt(it.x + x0, it.y + y0) } ?: guess
        // The re-detection is good to a few pixels; the rough guess from the preview needs a wider search.
        val search = EdgeFit.diagonal(rough) * (if (best != null) 0.01f else 0.025f)
        return snapEdges(full, rough, search.coerceAtLeast(4f))
    }

    /** Fits each side of [quad] to the real edge in [full] at full resolution. */
    fun snapEdges(full: Mat, quad: Quad, searchPx: Float): Quad {
        val r = searchPx.toInt() + 3
        val x0 = (quad.points.minOf { it.x }.toInt() - r).coerceIn(0, full.cols() - 1)
        val y0 = (quad.points.minOf { it.y }.toInt() - r).coerceIn(0, full.rows() - 1)
        val x1 = (quad.points.maxOf { it.x }.toInt() + r).coerceIn(x0 + 1, full.cols())
        val y1 = (quad.points.maxOf { it.y }.toInt() + r).coerceIn(y0 + 1, full.rows())
        val w = x1 - x0
        val h = y1 - y0

        val roi = full.submat(Rect(x0, y0, w, h))
        val gray = Mat()
        Imgproc.cvtColor(roi, gray, Imgproc.COLOR_RGBA2GRAY)
        roi.release()
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val gx = Mat()
        val gy = Mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
        val ax = FloatArray(w * h).also { gx.get(0, 0, it) }
        val ay = FloatArray(w * h).also { gy.get(0, 0, it) }
        listOf(gray, gx, gy).forEach { it.release() }

        val local = quad.map { Pt(it.x - x0, it.y - y0) }
        val snapped = EdgeFit.snap(local, searchPx) { x, y ->
            if (x < 0 || y < 0 || x >= w || y >= h) null else ax[y * w + x] to ay[y * w + x]
        }
        return snapped.map { Pt(it.x + x0, it.y + y0) }
    }

    private fun edgeMap(rgba: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)
        val sat = channels[1]

        val edges = cannyAuto(gray)
        val satEdges = cannyAuto(sat)
        Core.bitwise_or(edges, satEdges, edges)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)

        listOf(gray, rgb, hsv, satEdges, kernel).forEach { it.release() }
        channels.forEach { it.release() }
        return edges
    }

    /** Canny with thresholds taken from the image itself (Otsu), so it adapts to light and contrast. */
    private fun cannyAuto(channel: Mat): Mat {
        val blurred = Mat()
        Imgproc.GaussianBlur(channel, blurred, Size(5.0, 5.0), 0.0)
        val scratch = Mat()
        val otsu = Imgproc.threshold(blurred, scratch, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
        scratch.release()
        val high = otsu.coerceAtLeast(30.0)
        val out = Mat()
        Imgproc.Canny(blurred, out, high * 0.5, high)
        blurred.release()
        return out
    }

    private fun candidates(edges: Mat, w: Int, h: Int): List<Quad> {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()

        val imageArea = w.toFloat() * h
        val margin = 0.01f * max(w, h)
        val out = mutableListOf<Quad>()
        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < imageArea * minAreaShare || area > imageArea * 0.985f) {
                c.release(); continue
            }
            val quad = toQuad(c, area)
            c.release()
            if (quad == null || !quad.isReasonable()) continue
            val longSide = max(quad.outputWidth, quad.outputHeight)
            val shortSide = minOf(quad.outputWidth, quad.outputHeight)
            if (shortSide <= 0f || longSide / shortSide > 4f) continue
            // A print cut off by the frame edge can't be cropped properly; wait until it's fully in view.
            val onEdge = quad.points.count { it.x < margin || it.y < margin || it.x > w - margin || it.y > h - margin }
            if (onEdge >= 2) continue
            out += quad
        }
        return out
    }

    private fun toQuad(contour: MatOfPoint, area: Double): Quad? {
        val pts = contour.toArray()
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        hullIdx.release()

        val perimeter = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(hull, approx, 0.02 * perimeter, true)
        val quad = if (approx.total() == 4L) {
            Quad.fromCorners(approx.toArray().map { Pt(it.x.toFloat(), it.y.toFloat()) })
        } else {
            // Rounded or damaged corners: accept the tightest rotated rectangle if the shape fills it.
            val rect = Imgproc.minAreaRect(hull)
            val rectArea = rect.size.width * rect.size.height
            if (rectArea <= 0 || area / rectArea < 0.86) null
            else Array(4) { Point() }.also(rect::points).map { Pt(it.x.toFloat(), it.y.toFloat()) }
                .let(Quad::fromCorners)
        }
        hull.release()
        approx.release()
        return quad
    }
}

object Rectify {
    /** Warps [quad] (pixels of [src]) to a flat, upright rectangle at full resolution. */
    fun warp(src: Mat, quad: Quad, maxSide: Int = Int.MAX_VALUE): Mat {
        var w = quad.outputWidth
        var h = quad.outputHeight
        val fit = maxSide / max(w, h)
        if (fit < 1f) { w *= fit; h *= fit }
        val outW = w.roundToInt().coerceAtLeast(1)
        val outH = h.roundToInt().coerceAtLeast(1)

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
