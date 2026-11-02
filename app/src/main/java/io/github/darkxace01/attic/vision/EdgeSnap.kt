package io.github.darkxace01.attic.vision

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Fine-tunes outlines on the full-resolution photo. */
object EdgeSnap {
    /** Fits each side of [quad] to the real edge in [full] (RGBA) within [searchPx] of where it is. */
    fun snap(full: Mat, quad: Quad, searchPx: Float): Quad {
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
}
