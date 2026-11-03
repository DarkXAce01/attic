package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Enhance
import io.github.darkxace01.attic.vision.PrintHash
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.Rectify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

class PrintHashTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    private fun detailed(sc: Scenes, w: Int = 600, h: Int = 400): Mat {
        val m = sc.photo(w, h, color = sc.rnd.nextBoolean())
        repeat(40) {
            val c = Scalar(sc.rnd.nextDouble(20.0, 220.0), sc.rnd.nextDouble(20.0, 220.0), sc.rnd.nextDouble(20.0, 220.0), 255.0)
            val p = org.opencv.core.Point(sc.rnd.nextDouble(10.0, w - 10.0), sc.rnd.nextDouble(10.0, h - 10.0))
            if (it % 2 == 0) Imgproc.circle(m, p, sc.rnd.nextInt(6, 40), c, -1)
            else Imgproc.rectangle(m, p, org.opencv.core.Point(p.x + sc.rnd.nextInt(10, 80), p.y + sc.rnd.nextInt(10, 80)), c, -1)
        }
        return m
    }

    /** The same print scanned again: other light, a slightly different crop, noise, enhanced or not. */
    private fun rescan(sc: Scenes, print: Mat, i: Int): Mat {
        val w = print.cols().toFloat(); val h = print.rows().toFloat()
        val j = { s: Float -> (sc.rnd.nextFloat() * 2 - 1) * s }
        val q = Quad(Pt(j(0.02f * w), j(0.02f * h)), Pt(w + j(0.02f * w), j(0.02f * h)), Pt(w + j(0.02f * w), h + j(0.02f * h)), Pt(j(0.02f * w), h + j(0.02f * h)))
        val out = Rectify.warp(print, q, size = (w * 1.3f).toInt() to (h * 1.3f).toInt())
        val gain = 0.75 + 0.1 * i
        Core.multiply(out, Scalar(gain, gain * 0.95, gain * 1.05, 1.0), out)
        Core.add(out, Scalar(10.0 * i, 10.0 * i, 10.0 * i, 0.0), out)
        sc.addNoise(out, 6.0)
        return if (i % 2 == 0) Enhance.apply(out, Enhance.NATURAL).also { out.release() } else out
    }

    @Test
    fun aRescanMatchesItselfAndNotOtherPrints() {
        val sc = Scenes(11)
        val prints = List(12) { detailed(sc) }
        val hashes = prints.map { PrintHash.of(it) }
        var worstSame = 0
        prints.forEachIndexed { k, p ->
            repeat(3) { i ->
                val again = rescan(sc, p, i)
                worstSame = maxOf(worstSame, PrintHash.distance(hashes[k], PrintHash.of(again)))
                again.release()
            }
        }
        var closestOther = 64
        for (a in hashes.indices) for (b in hashes.indices) if (a < b) closestOther = minOf(closestOther, PrintHash.distance(hashes[a], hashes[b]))
        println("same print: at most $worstSame bits apart; different prints: at least $closestOther")
        assertTrue("same print $worstSame", worstSame <= PrintHash.SAME)
        assertTrue("different prints $closestOther", closestOther > PrintHash.SAME)
    }

    @Test
    fun aPrintScannedUpsideDownStillMatchesOneOfItsTurns() {
        val sc = Scenes(12)
        val p = detailed(sc)
        val upside = Mat().also { Core.rotate(p, it, Core.ROTATE_180) }
        val h = PrintHash.of(upside)
        assertFalse(PrintHash.same(PrintHash.of(p), h))
        assertTrue(PrintHash.allTurns(p).any { PrintHash.same(it, h) })
    }

    @Test
    fun aNewPageIsOneWhereMostPrintsAreNew() {
        val a = 0x0F0F0F0F0F0F0F0FL; val b = 0x3333333333333333L; val c = 0x5555555555555555L
        val d = 0x00FF00FF00FF00FFL; val e = 0x7E7E7E7E7E7E7E7EL
        assertFalse("same page", PrintHash.isNewPage(listOf(a, b, c), listOf(a, b, c)))
        assertFalse("a hand over one print", PrintHash.isNewPage(listOf(a, b, d), listOf(a, b, c)))
        assertTrue("page turned", PrintHash.isNewPage(listOf(d, e), listOf(a, b, c)))
        assertTrue("first page", PrintHash.isNewPage(listOf(a), emptyList()))
    }
}
