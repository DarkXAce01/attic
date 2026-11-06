package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Align
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.random.Random

class AlignTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
    }

    private fun rgba(f: File, width: Double): Mat {
        val bgr = Imgcodecs.imread(f.path)
        Imgproc.resize(bgr, bgr, Size(width, width * bgr.rows() / bgr.cols()), 0.0, 0.0, Imgproc.INTER_AREA)
        return Mat().also { Imgproc.cvtColor(bgr, it, Imgproc.COLOR_BGR2RGBA) }
    }

    /** An "old print" of [scene]: the [quad] part of it, flattened, faded, black and white, a little soft. */
    private fun oldPrint(scene: Mat, quad: List<Point>, w: Int, h: Int): Mat {
        val m = Imgproc.getPerspectiveTransform(MatOfPoint2f(*quad.toTypedArray()), MatOfPoint2f(Point(0.0, 0.0), Point(w.toDouble(), 0.0), Point(w.toDouble(), h.toDouble()), Point(0.0, h.toDouble())))
        val out = Mat()
        Imgproc.warpPerspective(scene, out, m, Size(w.toDouble(), h.toDouble()))
        val g = Mat(); Imgproc.cvtColor(out, g, Imgproc.COLOR_RGBA2GRAY)
        g.convertTo(g, -1, 0.6, 60.0)
        Imgproc.GaussianBlur(g, g, Size(3.0, 3.0), 0.0)
        Imgproc.cvtColor(g, out, Imgproc.COLOR_GRAY2RGBA)
        return out
    }

    @Test
    fun todaysPhotoLandsOnTheOldPrint() {
        val files = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(600).take(12)
        assumeTrue(files.isNotEmpty())
        val rnd = Random(4)
        var matched = 0; var good = 0
        for (f in files) {
            val now = rgba(f, 1600.0)
            val w = now.cols().toDouble(); val h = now.rows().toDouble()
            // The old photo was taken from a bit to one side: its frame is a tilted quad in today's.
            fun j() = (rnd.nextDouble() - 0.5) * 0.08
            val quad = listOf(Point(w * (0.2 + j()), h * (0.2 + j())), Point(w * (0.8 + j()), h * (0.18 + j())), Point(w * (0.82 + j()), h * (0.8 + j())), Point(w * (0.18 + j()), h * (0.82 + j())))
            val ow = 700; val oh = (700 * (h * 0.6) / (w * 0.6)).toInt()
            val old = oldPrint(now, quad, ow, oh)
            // Since then: a third of the view has changed (a new building, a tree grown), and the light.
            val other = rgba(files[(files.indexOf(f) + 5) % files.size], 1600.0)
            val cw = (w * 0.45).toInt(); val ch = (h * 0.7).toInt()
            val cx = if (rnd.nextBoolean()) 0 else (w - cw).toInt()
            other.submat(Rect(0, 0, cw, minOf(ch, other.rows()))).copyTo(now.submat(Rect(cx, 0, cw, minOf(ch, other.rows()))))
            now.convertTo(now, -1, 1.1, -15.0)
            other.release()
            // Lined up by eye, roughly.
            val gx = (w * 0.2 + w * j()).toInt(); val gy = (h * 0.2 + h * j()).toInt()
            val guess = Rect(gx, gy, (w * 0.6).toInt(), (w * 0.6 * oh / ow).toInt())
            val r = Align.toOld(old, now, guess)
            // How far off each corner is, against where the old print really was, as a share of the width.
            val err = r.corners.zip(quad).maxOf { (p, q) -> kotlin.math.hypot(p.first - q.x, p.second - q.y) } / w
            val held = listOf(guess.x to guess.y, guess.x + guess.width to guess.y, guess.x + guess.width to guess.y + guess.height, guess.x to guess.y + guess.height)
                .zip(quad).maxOf { (p, q) -> kotlin.math.hypot(p.first - q.x, p.second - q.y) } / w
            println("align ${f.name}: matched=${r.matched} corner error=${"%.4f".format(err)} as held=${"%.4f".format(held)}")
            if (r.matched) matched++
            if (err < 0.01) good++
            listOf(now, old, r.image).forEach { it.release() }
        }
        assertTrue("matched $matched of ${files.size}", matched >= files.size * 3 / 4)
        assertTrue("good $good of ${files.size}", good >= files.size * 3 / 4)
    }

    @Test
    fun anUnrelatedPhotoKeepsTheFramingAsHeld() {
        val rnd = java.util.Random(1)
        val noise = Mat(900, 1200, CvType.CV_8UC4)
        Core.randu(noise, 0.0, 255.0)
        val old = Mat(500, 700, CvType.CV_8UC4, Scalar(120.0, 120.0, 120.0, 255.0))
        for (k in 0 until 40) Imgproc.circle(old, Point(rnd.nextInt(700).toDouble(), rnd.nextInt(500).toDouble()), 10 + rnd.nextInt(40), Scalar(rnd.nextInt(255).toDouble(), 0.0, 0.0, 255.0), -1)
        val r = Align.toOld(old, noise, Rect(200, 150, 700, 500))
        assertFalse(r.matched)
        assertTrue(r.image.cols() == 700 && r.image.rows() == 500)
    }

    @Test
    fun aCollapsedFitIsRejected() {
        // Squashes everything onto a line: not a viewpoint anyone stood at.
        val h = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.001, 0.0, 0.0, 0.0, 1.0)
        assertFalse(Align.plausible(h, 700.0, 500.0, doubleArrayOf(100.0, 100.0, 700.0, 500.0)))
        val id = doubleArrayOf(1.0, 0.0, -100.0, 0.0, 1.0, -100.0, 0.0, 0.0, 1.0)
        assertTrue(Align.plausible(id, 700.0, 500.0, doubleArrayOf(100.0, 100.0, 700.0, 500.0)))
    }
}
