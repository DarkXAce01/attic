package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Enhance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class EnhanceTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /** A soft, faded, slightly orange print: a sky gradient, a dark figure, a pale disc. */
    private fun fadedPrint(): Mat {
        val m = Mat(480, 640, CvType.CV_8UC4)
        for (y in 0 until 480) {
            val v = 200.0 - y * 0.25
            Imgproc.line(m, Point(0.0, y.toDouble()), Point(639.0, y.toDouble()), Scalar(v, v * 0.95, v * 0.9, 255.0))
        }
        Imgproc.rectangle(m, Point(100.0, 200.0), Point(220.0, 440.0), Scalar(40.0, 45.0, 60.0, 255.0), -1)
        Imgproc.circle(m, Point(450.0, 150.0), 70, Scalar(230.0, 220.0, 190.0, 255.0), -1)
        Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), 2.0)
        // Fade: lift the blacks, squash the range, warm cast.
        Core.multiply(m, Scalar(0.6, 0.55, 0.45, 1.0), m)
        Core.add(m, Scalar(70.0, 55.0, 40.0, 0.0), m)
        return m
    }

    private fun lightness(rgba: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        return gray
    }

    private fun std(m: Mat): Double {
        val mean = MatOfDouble()
        val sd = MatOfDouble()
        Core.meanStdDev(m, mean, sd)
        return sd.toArray()[0]
    }

    @Test
    fun keepsSizeTypeAndAlpha() {
        val src = fadedPrint()
        for (level in listOf(Enhance.OFF, Enhance.NATURAL, Enhance.STRONG)) {
            val out = Enhance.apply(src, level)
            assertEquals(src.size(), out.size())
            assertEquals(CvType.CV_8UC4, out.type())
            val alpha = ArrayList<Mat>().also { Core.split(out, it) }[3]
            assertEquals(255.0, Core.minMaxLoc(alpha).minVal, 0.0)
        }
    }

    @Test
    fun aFadedPrintGetsItsContrastAndColourBack() {
        val src = fadedPrint()
        val before = lightness(src)
        val beforeMean = Core.mean(src).`val`
        for (level in listOf(Enhance.NATURAL, Enhance.STRONG)) {
            val out = Enhance.apply(src, level)
            val after = lightness(out)
            assertTrue("contrast ${std(before)} -> ${std(after)} at $level", std(after) > std(before) * 1.3)
            val mm = Core.minMaxLoc(after)
            assertTrue("blacks back at $level: ${mm.minVal}", mm.minVal < 40)
            val afterMean = Core.mean(out).`val`
            assertTrue("cast smaller at $level", abs(afterMean[0] - afterMean[2]) < abs(beforeMean[0] - beforeMean[2]))
        }
    }

    @Test
    fun flatAreasDontGetNoisier() {
        // A print with a full range of tones above an even grey wall with a little sensor noise: the
        // enhancement must not turn that noise into grain.
        val src = Mat(400, 400, CvType.CV_8UC4)
        for (x in 0 until 400) {
            val v = 20 + x * 215.0 / 399
            Imgproc.line(src, Point(x.toDouble(), 0.0), Point(x.toDouble(), 199.0), Scalar(v, v, v, 255.0))
        }
        val wall = Mat(200, 400, CvType.CV_8UC4)
        Core.randn(wall, 128.0, 1.5)
        Imgproc.cvtColor(wall, wall, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(wall, wall, Imgproc.COLOR_GRAY2RGBA)
        wall.copyTo(src.rowRange(200, 400))
        val patch = { m: Mat -> std(lightness(m).submat(250, 350, 50, 350)) }
        val before = patch(src)
        val natural = patch(Enhance.apply(src, Enhance.NATURAL))
        val strong = patch(Enhance.apply(src, Enhance.STRONG))
        assertTrue("natural noise $before -> $natural", natural < before * 1.8)
        assertTrue("strong noise $before -> $strong", strong < before * 1.8)
    }

    @Test
    fun originalIsAnExactCopy() {
        val src = fadedPrint()
        val out = Enhance.apply(src, Enhance.OFF)
        val diff = Mat()
        Core.absdiff(src, out, diff)
        assertEquals(0.0, Core.sumElems(diff).`val`.sum(), 0.0)
    }
}
