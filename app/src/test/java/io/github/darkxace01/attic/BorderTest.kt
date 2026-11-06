package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Border
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Prints with borders drawn around Unsplash photos (never anyone's own), and photos without. */
class BorderTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
    }

    private fun photo(i: Int, w: Int, h: Int): Mat? {
        val f = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.getOrNull(300 + i) ?: return null
        val bgr = Imgcodecs.imread(f.path)
        val m = Mat()
        Imgproc.resize(bgr, m, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        bgr.release()
        return Mat().also { Imgproc.cvtColor(m, it, Imgproc.COLOR_BGR2RGBA); m.release() }
    }

    /** [i]'s photo on paper of [paper] colour with borders (shares of the print's size), grain and a soft scan. */
    private fun bordered(i: Int, paper: Scalar, l: Double, t: Double, r: Double, b: Double, writing: Boolean = false): Mat? {
        val w = 1200; val h = 1500
        val iw = (w * (1 - l - r)).toInt(); val ih = (h * (1 - t - b)).toInt()
        val pic = photo(i, iw, ih) ?: return null
        val print = Mat(h, w, CvType.CV_8UC4, paper)
        pic.copyTo(print.submat(Rect((w * l).toInt(), (h * t).toInt(), iw, ih)))
        if (writing) {
            Imgproc.putText(print, "Shimla, May 87", Point(w * 0.18, h * (1 - b * 0.45)), Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX, 2.4, Scalar(40.0, 50.0, 110.0, 255.0), 3, Imgproc.LINE_AA)
        }
        val noise = Mat(h, w, CvType.CV_8UC4)
        Core.randn(noise, 0.0, 3.0)
        Core.add(print, noise, print)
        Imgproc.GaussianBlur(print, print, Size(3.0, 3.0), 0.8)
        listOf(pic, noise).forEach { it.release() }
        return print
    }

    private val WHITE = Scalar(238.0, 234.0, 222.0, 255.0)

    @Test
    fun aWhiteBorderIsFound() {
        val p = bordered(0, WHITE, 0.05, 0.04, 0.05, 0.04)
        assumeTrue(p != null)
        val b = Border.find(p!!)
        assertNotNull(b)
        assertEquals(0.05f, b!!.left, 0.01f); assertEquals(0.04f, b.top, 0.01f)
        assertEquals(0.05f, b.right, 0.01f); assertEquals(0.04f, b.bottom, 0.01f)
        assertFalse(b.writing)
    }

    @Test
    fun aPolaroidAndItsCaption() {
        val plain = bordered(1, WHITE, 0.06, 0.06, 0.06, 0.24) ?: return
        val b = Border.find(plain)
        assertNotNull(b)
        assertEquals(0.24f, b!!.bottom, 0.012f)
        assertFalse(b.writing)
        val written = bordered(1, WHITE, 0.06, 0.06, 0.06, 0.24, writing = true)!!
        val c = Border.find(written)
        assertNotNull(c)
        assertEquals(0.24f, c!!.bottom, 0.012f)
        assertTrue("the caption should be noticed", c.writing)
    }

    @Test
    fun aBlackBorderToo() {
        val p = bordered(2, Scalar(18.0, 18.0, 20.0, 255.0), 0.03, 0.03, 0.03, 0.03) ?: return
        val b = Border.find(p)
        assertNotNull(b)
        assertEquals(0.03f, b!!.left, 0.008f)
    }

    @Test
    fun photosWithoutBordersHaveNone() {
        var found = 0
        var n = 0
        for (i in 10 until 50) {
            val p = photo(i, 1200, 900) ?: continue
            n++
            if (Border.find(p) != null) { found++; println("photo ${300 + i} taken as bordered: ${Border.find(p)}") }
            p.release()
        }
        assumeTrue(n > 0)
        println("borderless photos taken as bordered: $found of $n")
        assertEquals(0, found)
    }

    @Test
    fun theInnerOutlineFollowsThePrint() {
        val q = Quad(Pt(100f, 100f), Pt(1100f, 100f), Pt(1100f, 1600f), Pt(100f, 1600f))
        val i = Border.inner(q, Border.Inset(0.1f, 0.1f, 0.1f, 0.2f, false))
        assertEquals(200f, i.tl.x, 0.5f); assertEquals(250f, i.tl.y, 0.5f)
        assertEquals(1000f, i.br.x, 0.5f); assertEquals(1300f, i.br.y, 0.5f)
    }
}
