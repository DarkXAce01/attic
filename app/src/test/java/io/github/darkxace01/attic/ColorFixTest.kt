package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.ColorFix
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class ColorFixTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** A neutral grey ramp, then faded the way old prints fade: lifted blacks, orange cast. */
    private fun fadedPrint(): Mat {
        val m = Mat(300, 400, CvType.CV_8UC4)
        for (x in 0 until 400) {
            val v = x * 255.0 / 399
            Imgproc.line(m, Point(x.toDouble(), 0.0), Point(x.toDouble(), 299.0), Scalar(v, v, v, 255.0))
        }
        val faded = Mat()
        // R keeps more range than G, B loses most: an orange cast with weak blacks.
        Core.multiply(m, Scalar(0.75, 0.6, 0.45, 1.0), faded)
        Core.add(faded, Scalar(60.0, 50.0, 40.0, 0.0), faded)
        m.release()
        return faded
    }

    private fun mean(m: Mat) = Core.mean(m).`val`

    @Test
    fun removesTheCastAndRestoresContrast() {
        val faded = fadedPrint()
        val before = mean(faded)
        val fixed = ColorFix.apply(faded)
        val after = mean(fixed)

        val castBefore = abs(before[0] - before[2])
        val castAfter = abs(after[0] - after[2])
        assertTrue("cast before ${castBefore.toInt()}, after ${castAfter.toInt()}", castAfter < castBefore / 3)

        val channels = ArrayList<Mat>().also { Core.split(fixed, it) }
        val mm = Core.minMaxLoc(channels[1])
        assertTrue("green range ${mm.minVal}..${mm.maxVal}", mm.minVal < 25 && mm.maxVal > 230)
    }

    @Test
    fun aGoodPrintIsLeftAlmostAlone() {
        val m = Mat(200, 256, CvType.CV_8UC4)
        for (x in 0 until 256) {
            Imgproc.line(m, Point(x.toDouble(), 0.0), Point(x.toDouble(), 199.0), Scalar(x.toDouble(), x * 0.9, x * 0.8, 255.0))
        }
        val before = mean(m)
        val after = mean(ColorFix.apply(m))
        for (c in 0 until 3) assertTrue("channel $c moved ${before[c]} → ${after[c]}", abs(before[c] - after[c]) < 30)
    }
}
