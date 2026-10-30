package io.github.darkxace01.attic

import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Writes zoomed, gridded crops of imgs/ so print corners can be read off precisely. */
class ZoomGrid {
    @Test
    fun write() {
        val spec = System.getProperty("attic.zoom") ?: ""
        assumeTrue(spec.isNotEmpty())
        nu.pattern.OpenCV.loadLocally()
        // name:x0:y0:x1:y1 in live (720 x 1280) pixels
        val (name, a, b, c, d) = spec.split(":")
        val x0 = a.toInt(); val y0 = b.toInt(); val x1 = c.toInt(); val y1 = d.toInt()
        val img = Imgcodecs.imread("../imgs/$name.jpg")
        val s = img.rows() / 1280.0
        val roi = Mat(img, Rect((x0 * s).toInt(), (y0 * s).toInt(), ((x1 - x0) * s).toInt(), ((y1 - y0) * s).toInt()))
        val z = 2.0
        val k = z / s
        val out = Mat()
        Imgproc.resize(roi, out, Size(), k, k, Imgproc.INTER_CUBIC)
        for (x in x0..x1) if (x % 10 == 0) {
            val px = (x - x0) * z
            Imgproc.line(out, Point(px, 0.0), Point(px, out.rows().toDouble()), if (x % 50 == 0) Scalar(0.0, 255.0, 255.0) else Scalar(0.0, 140.0, 140.0), 1)
            if (x % 50 == 0) Imgproc.putText(out, "$x", Point(px + 2, 14.0), 0, 0.45, Scalar(0.0, 255.0, 255.0), 1)
        }
        for (y in y0..y1) if (y % 10 == 0) {
            val py = (y - y0) * z
            Imgproc.line(out, Point(0.0, py), Point(out.cols().toDouble(), py), if (y % 50 == 0) Scalar(255.0, 0.0, 255.0) else Scalar(140.0, 0.0, 140.0), 1)
            if (y % 50 == 0) Imgproc.putText(out, "$y", Point(2.0, py - 2), 0, 0.45, Scalar(255.0, 0.0, 255.0), 1)
        }
        File("build/detector/zoom").mkdirs()
        Imgcodecs.imwrite("build/detector/zoom/${spec.replace(":", "_")}.png", out)
    }
}
