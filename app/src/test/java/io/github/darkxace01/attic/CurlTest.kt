package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Curl
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.Rectify
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.random.Random

/**
 * A curled print, drawn: an Unsplash photo laid on a dark album page with its top and bottom (or sides)
 * bowed out, as a print lifting in the middle photographs. Uncurled, it should match the photo far better
 * than a four-corner flatten does; a flat print should be left to the four-corner flatten.
 */
class CurlTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
        const val W = 1400; const val H = 1000; const val X0 = 500; const val Y0 = 420
    }

    private fun photo(i: Int): Mat? {
        val f = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.getOrNull(200 + i) ?: return null
        val bgr = Imgcodecs.imread(f.path)
        val m = Mat()
        Imgproc.resize(bgr, m, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        bgr.release()
        return Mat().also { Imgproc.cvtColor(m, it, Imgproc.COLOR_BGR2RGBA); m.release() }
    }

    /** [print] on a page, bowed by [bow] px: top and bottom when [across], else left and right. */
    private fun scene(print: Mat, bow: Double, across: Boolean, seed: Int): Mat {
        val sw = 2400; val sh = 1900
        val page = Mat(sh, sw, CvType.CV_8UC4, Scalar(70.0, 52.0, 40.0, 255.0))
        val noise = Mat(sh, sw, CvType.CV_8UC4)
        Core.randn(noise, 0.0, 6.0)
        Core.add(page, noise, page)
        val mx = Mat(sh, sw, CvType.CV_32F); val my = Mat(sh, sw, CvType.CV_32F)
        val bx = FloatArray(sw * sh) { -1f }; val by = FloatArray(sw * sh) { -1f }
        for (y in 0 until sh) for (x in 0 until sw) {
            val u: Double; val v: Double
            if (across) {
                u = (x - X0).toDouble() / W
                val t = 4 * u * (1 - u)
                val top = Y0 - bow * t; val bottom = Y0 + H + bow * t
                v = (y - top) / (bottom - top)
            } else {
                v = (y - Y0).toDouble() / H
                val t = 4 * v * (1 - v)
                val left = X0 - bow * t; val right = X0 + W + bow * t
                u = (x - left) / (right - left)
            }
            if (u in 0.0..1.0 && v in 0.0..1.0) { bx[y * sw + x] = (u * (W - 1)).toFloat(); by[y * sw + x] = (v * (H - 1)).toFloat() }
        }
        mx.put(0, 0, bx); my.put(0, 0, by)
        val drawn = Mat()
        Imgproc.remap(print, drawn, mx, my, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0))
        // Where the print is (alpha), over the page.
        val alpha = Mat()
        Core.extractChannel(drawn, alpha, 3)
        drawn.copyTo(page, alpha)
        listOf(noise, mx, my, drawn, alpha).forEach { it.release() }
        return page
    }

    private fun quad() = Quad(Pt(X0.toFloat(), Y0.toFloat()), Pt((X0 + W).toFloat(), Y0.toFloat()), Pt((X0 + W).toFloat(), (Y0 + H).toFloat()), Pt(X0.toFloat(), (Y0 + H).toFloat()))

    /** Mean difference from the photo, with both at the photo's size; the outer 2% left out. */
    private fun error(flat: Mat, print: Mat): Double {
        val a = Mat()
        Imgproc.resize(flat, a, print.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val r = org.opencv.core.Rect((W * 0.02).toInt(), (H * 0.02).toInt(), (W * 0.96).toInt(), (H * 0.96).toInt())
        val d = Mat()
        Core.absdiff(a.submat(r), print.submat(r), d)
        val m = Core.mean(d)
        listOf(a, d).forEach { it.release() }
        return (m.`val`[0] + m.`val`[1] + m.`val`[2]) / 3
    }

    @Test
    fun aCurledPrintComesOutStraight() {
        assumeTrue(photo(0) != null)
        val rnd = Random(3)
        var seen = 0
        for (i in 0 until 8) {
            val print = photo(i) ?: continue
            val across = i % 2 == 0
            val bow = 18.0 + rnd.nextDouble() * 30
            val s = scene(print, bow, across, i)
            val straight = Rectify.warp(s, quad())
            val t = System.currentTimeMillis()
            val uncurled = Curl.flatten(s, quad())
            val ms = System.currentTimeMillis() - t
            System.getProperty("dust.log")?.let { dirOut ->
                Imgcodecs.imwrite("$dirOut/curl_${i}_straight.png", straight)
                uncurled?.let { Imgcodecs.imwrite("$dirOut/curl_${i}_uncurled.png", it) }
                Imgcodecs.imwrite("$dirOut/curl_${i}_print.png", print)
                File("$dirOut/curl.txt").appendText("$i bows " + Curl.measure(s, quad())?.joinToString { it.joinToString(",") { v -> "%.1f".format(v) } } + "\n")
            }
            // A dark, busy print on a dark page can hide its edges; then it's flattened as before.
            if (uncurled == null) { println("photo $i bow ${bow.toInt()}: edges not clear enough, left as it was"); continue }
            seen++
            val e0 = error(straight, print); val e1 = error(uncurled, print)
            println("photo $i bow ${bow.toInt()} ${if (across) "top/bottom" else "sides"}: four corners %.1f, uncurled %.1f, %d ms".format(e0, e1, ms))
            assertTrue("uncurling should help a lot: $e0 -> $e1", e1 < e0 * 0.6)
            listOf(print, s, straight, uncurled).forEach { it.release() }
        }
        assertTrue("found $seen of 8 curled prints", seen >= 6)
    }

    @Test
    fun aFlatPrintIsLeftAlone() {
        assumeTrue(photo(0) != null)
        var curled = 0
        for (i in 0 until 6) {
            val print = photo(10 + i) ?: continue
            val s = scene(print, 0.0, true, i)
            Curl.flatten(s, quad())?.let { curled++; it.release() }
            listOf(print, s).forEach { it.release() }
        }
        println("flat prints taken as curled: $curled of 6")
        assertTrue(curled == 0)
    }
}
