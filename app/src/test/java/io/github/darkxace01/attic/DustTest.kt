package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Dust
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Dust drawn onto Unsplash photos (never anyone's own): specks light and dark, and a few hairs. Most of it
 * should go, and the same photos without dust should come out nearly untouched.
 */
class DustTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
    }

    private fun photos(): List<Mat> = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(100).take(30).map { f ->
        val bgr = Imgcodecs.imread(f.path)
        val s = 1400.0 / maxOf(bgr.cols(), bgr.rows())
        Imgproc.resize(bgr, bgr, Size(), s, s, Imgproc.INTER_AREA)
        Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2RGBA); bgr.release() }
    }

    /** Draws dust onto a copy of [img]; returns it and where the dust is. */
    private fun dusty(img: Mat, rnd: Random): Pair<Mat, Mat> {
        val out = img.clone()
        val mask = Mat.zeros(img.size(), CvType.CV_8U)
        val w = img.cols(); val h = img.rows()
        repeat(50) {
            val c = Point(rnd.nextInt(5, w - 5).toDouble(), rnd.nextInt(5, h - 5).toDouble())
            val r = rnd.nextInt(1, 4)
            val g = rnd.nextInt(0, 40).toDouble()
            val v = if (rnd.nextFloat() < 0.6f) 255.0 - g else 25.0 + g
            Imgproc.circle(out, c, r, Scalar(v, v, v, 255.0), -1, Imgproc.LINE_AA)
            Imgproc.circle(mask, c, r + 1, Scalar(255.0), -1)
        }
        repeat(4) {
            var x = rnd.nextInt(w).toDouble(); var y = rnd.nextInt(h).toDouble(); var a = rnd.nextDouble() * 6.28
            val pts = ArrayList<Point>()
            repeat(30) {
                a += (rnd.nextDouble() - 0.5) * 0.3; x += cos(a) * 4; y += sin(a) * 4
                pts += Point(x, y)
            }
            val v = if (rnd.nextBoolean()) 30.0 else 240.0
            Imgproc.polylines(out, listOf(MatOfPoint(*pts.toTypedArray())), false, Scalar(v, v, v, 255.0), 1, Imgproc.LINE_AA)
            Imgproc.polylines(mask, listOf(MatOfPoint(*pts.toTypedArray())), false, Scalar(255.0), 3)
        }
        return out to mask
    }

    private fun meanAbs(a: Mat, b: Mat, mask: Mat?): Double {
        val d = Mat()
        Core.absdiff(a, b, d)
        val m = if (mask != null) Core.mean(d, mask) else Core.mean(d)
        d.release()
        return (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3
    }

    @Test
    fun sweep() {
        assumeTrue(System.getProperty("dust.log") != null)
        val list = photos().take(12)
        for ((ratio, minC, minA) in listOf(Triple(6.0, 14.0, 4), Triple(8.0, 16.0, 4), Triple(10.0, 18.0, 5), Triple(8.0, 20.0, 6), Triple(12.0, 20.0, 4))) {
            Dust.RATIO = ratio; Dust.MIN_CONTRAST = minC; Dust.MIN_AREA = minA
            val rnd = Random(1)
            var fixed = 0.0; var harm = 0.0; var fs = 0
            for (img in list) {
                val (d, mask) = dusty(img, rnd)
                val r = Dust.clean(d)
                fixed += 1 - meanAbs(r.image, img, mask) / meanAbs(d, img, mask)
                val c = Dust.clean(img)
                harm += meanAbs(c.image, img, null); fs += c.specks
                listOf(d, mask, r.image, c.image).forEach { it.release() }
            }
            File(System.getProperty("dust.log")).appendText("$ratio $minC $minA: fixed %.3f harm %.3f false %d\n".format(fixed / list.size, harm / list.size, fs / list.size))
        }
    }

    @Test
    fun dustGoesAndCleanPhotosStayAlone() {
        val list = photos()
        assumeTrue("Unsplash photos not on this machine", list.size >= 10)
        val rnd = Random(1)
        var fixed = 0.0; var harm = 0.0; var worstHarm = 0.0; var falseSpecks = 0
        var ms = 0L
        for (img in list) {
            val (d, mask) = dusty(img, rnd)
            val t = System.currentTimeMillis()
            val r = Dust.clean(d)
            ms += System.currentTimeMillis() - t
            fixed += 1 - meanAbs(r.image, img, mask) / meanAbs(d, img, mask)
            val c = Dust.clean(img)
            val hm = meanAbs(c.image, img, null)
            harm += hm; worstHarm = maxOf(worstHarm, hm); falseSpecks += c.specks
            listOf(d, mask, r.image, c.image).forEach { it.release() }
            System.getProperty("dust.log")?.let { File(it).appendText("${System.currentTimeMillis() - t} ms, specks ${r.specks}/${c.specks}\n") }
        }
        val n = list.size
        println("dust fixed %.3f, change to clean photos %.3f (worst %.3f), specks found on clean photos %.1f each, %d ms each"
            .format(fixed / n, harm / n, worstHarm, falseSpecks.toDouble() / n, ms / n))
        assertTrue(fixed / n > 0.6)
        assertTrue(harm / n < 0.15)
    }
}
