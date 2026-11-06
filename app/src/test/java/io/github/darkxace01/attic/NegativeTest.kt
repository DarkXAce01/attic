package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Negative
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Colour negatives made from Unsplash photos with a simple film model: an orange base, and each colour's
 * density growing with the log of its light, at its own contrast. Developed, they should look like the
 * photo again: same picture (high correlation) and about the same colour.
 */
class NegativeTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
        private val BASE = floatArrayOf(0.92f, 0.62f, 0.38f)
        private val GAMMA = floatArrayOf(1.5f, 1.7f, 1.9f)
    }

    private fun negativeOf(rgb: Mat): Mat {
        val w = rgb.cols(); val h = rgb.rows()
        val px = ByteArray(w * h * 3).also { rgb.get(0, 0, it) }
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            for (c in 0..2) {
                val p = (px[i * 3 + c].toInt() and 0xFF) / 255f
                val d = GAMMA[c] * log10(1 + 9 * p)
                val t = BASE[c] * 10f.pow(-d)
                out[i * 4 + c] = (t * 255).toInt().coerceIn(0, 255).toByte()
            }
            out[i * 4 + 3] = -1
        }
        return Mat(h, w, CvType.CV_8UC4).also { it.put(0, 0, out) }
    }

    @Test
    fun aNegativeDevelopsBackIntoThePhoto() {
        val files = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(400).take(10)
        assumeTrue(files.isNotEmpty())
        var worstCorr = 1.0; var meanCast = 0.0
        val corrs = ArrayList<Double>()
        for (f in files) {
            val bgr = Imgcodecs.imread(f.path)
            Imgproc.resize(bgr, bgr, Size(900.0, 900.0 * bgr.rows() / bgr.cols()), 0.0, 0.0, Imgproc.INTER_AREA)
            val rgb = Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2RGB) }
            val neg = negativeOf(rgb)
            val pos = Negative.develop(neg)
            val posRgb = Mat().also { Imgproc.cvtColor(pos, it, Imgproc.COLOR_RGBA2RGB) }
            // Same picture: correlation of lightness.
            fun lab(m: Mat) = Mat().also { Imgproc.cvtColor(m, it, Imgproc.COLOR_RGB2Lab) }
            val a = lab(rgb); val b = lab(posRgb)
            val la = Mat(); val lb = Mat()
            Core.extractChannel(a, la, 0); Core.extractChannel(b, lb, 0)
            la.convertTo(la, CvType.CV_32F); lb.convertTo(lb, CvType.CV_32F)
            val corr = Mat()
            Imgproc.matchTemplate(la, lb, corr, Imgproc.TM_CCOEFF_NORMED)
            val c = corr.get(0, 0)[0]
            // About the same colour: the mean a* and b* differences.
            val ma = Core.mean(a); val mb = Core.mean(b)
            val cast = abs(ma.`val`[1] - mb.`val`[1]) + abs(ma.`val`[2] - mb.`val`[2])
            println("${f.name}: lightness correlation %.3f, colour off by %.1f".format(c, cast))
            corrs += c
            worstCorr = minOf(worstCorr, c); meanCast += cast / files.size
            listOf(bgr, rgb, neg, pos, posRgb, a, b, la, lb, corr).forEach { it.release() }
        }
        println("worst correlation %.3f, mean colour difference %.1f".format(worstCorr, meanCast))
        // A hazy picture with no black in it can drift; most come back close.
        assertTrue(worstCorr > 0.8)
        assertTrue(corrs.sorted()[corrs.size / 2] > 0.97)
        assertTrue(meanCast < 20)
    }

}
