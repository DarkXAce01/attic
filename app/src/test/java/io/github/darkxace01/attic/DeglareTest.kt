package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Deglare
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.Rectify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File

class DeglareTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    /**
     * Three shots of one glossy print from slightly different angles, with a lamp's reflection in a
     * different place on the print in each. Outlines are off by a couple of pixels, as at capture.
     */
    @Test
    fun glareIsReplacedFromTheOtherShotsAndTheRestIsUntouched() {
        val sc = Scenes(3)
        val print = detailed(sc, 900, 600)
        val size = print.cols() to print.rows()
        val shots = listOf(
            Triple(Pt(480f, 640f), 4f, Pt(0.35f, 0.45f)),
            Triple(Pt(455f, 625f), 1f, Pt(0.62f, 0.55f)),
            Triple(Pt(505f, 650f), 7f, Pt(0.15f, 0.3f)),
        )
        val r = kotlin.random.Random(5)
        val flats = shots.mapIndexed { i, (c, angle, spot) ->
            val scene = sc.background(960, 1280, Scalar(40.0, 34.0, 30.0, 255.0), grain = 8.0)
            val q = sc.place(scene, print.clone(), c, angle, tilt = 0.04f + 0.02f * i)
            val p = Pt(q.tl.x + (q.tr.x - q.tl.x) * spot.x + (q.bl.x - q.tl.x) * spot.y, q.tl.y + (q.tr.y - q.tl.y) * spot.x + (q.bl.y - q.tl.y) * spot.y)
            sc.glare(scene, p, 110.0, 70.0, 20.0, 190.0)
            val shot = sc.camera(scene)
            // Each shot's exposure differs a little.
            Core.multiply(shot, Scalar(1.0 + 0.04 * i, 1.0 + 0.04 * i, 1.0 + 0.04 * i, 1.0), shot)
            val rough = q.map { Pt(it.x + r.nextFloat() * 4 - 2, it.y + r.nextFloat() * 4 - 2) }
            Rectify.warp(shot, rough, size = size).also { shot.release(); scene.release() }
        }
        assertEquals(size, flats[1].cols() to flats[1].rows())

        val merged = Deglare.merge(flats[0], flats.drop(1))
        fun gray(m: Mat) = Mat().also { Imgproc.cvtColor(m, it, Imgproc.COLOR_RGBA2GRAY); it.convertTo(it, CvType.CV_32F) }
        val truth = gray(print)
        val before = gray(flats[0])
        val after = gray(merged)
        // Where the main shot has glare, and everywhere else, away from the edges the outline error touches.
        val glare = Mat().also { m -> Core.subtract(before, truth, m); Imgproc.threshold(m, m, 25.0, 255.0, Imgproc.THRESH_BINARY); m.convertTo(m, CvType.CV_8U) }
        val inner = Mat.zeros(glare.size(), CvType.CV_8U).also { m -> Imgproc.rectangle(m, org.opencv.core.Point(30.0, 30.0), org.opencv.core.Point(size.first - 30.0, size.second - 30.0), Scalar(255.0), -1) }
        val rest = Mat().also { m -> Core.bitwise_not(glare, m); Core.bitwise_and(m, inner, m) }
        Core.bitwise_and(glare, inner, glare)
        fun err(m: Mat, mask: Mat) = Mat().let { d -> Core.absdiff(m, truth, d); Core.mean(d, mask).`val`[0].also { d.release() } }
        val gBefore = err(before, glare); val gAfter = err(after, glare)
        val rBefore = err(before, rest); val rAfter = err(after, rest)
        println("glare area ${"%.1f".format(Core.countNonZero(glare) * 100.0 / (size.first * size.second))}%: error ${"%.1f".format(gBefore)} -> ${"%.1f".format(gAfter)}; elsewhere ${"%.2f".format(rBefore)} -> ${"%.2f".format(rAfter)}")
        if (System.getProperty("attic.explain") != null) {
            File("build/detector").mkdirs()
            val both = Mat(); Core.vconcat(listOf(flats[0], merged), both)
            Imgproc.cvtColor(both, both, Imgproc.COLOR_RGBA2BGR)
            org.opencv.imgcodecs.Imgcodecs.imwrite("build/detector/deglare.jpg", both)
        }
        assertTrue(Core.countNonZero(glare) > 5000)
        assertTrue("glare error $gBefore -> $gAfter", gAfter < gBefore * 0.3)
        assertTrue("elsewhere $rBefore -> $rAfter", rAfter < rBefore + 1.0)
    }

    /** A photo with the kind of detail real ones have: lots of small shapes. */
    private fun detailed(sc: Scenes, w: Int, h: Int): Mat {
        val m = sc.photo(w, h, color = true)
        repeat(60) {
            val c = Scalar(sc.rnd.nextDouble(20.0, 200.0), sc.rnd.nextDouble(20.0, 200.0), sc.rnd.nextDouble(20.0, 200.0), 255.0)
            val p = org.opencv.core.Point(sc.rnd.nextDouble(20.0, w - 20.0), sc.rnd.nextDouble(20.0, h - 20.0))
            if (it % 2 == 0) Imgproc.circle(m, p, sc.rnd.nextInt(4, 20), c, -1)
            else Imgproc.rectangle(m, p, org.opencv.core.Point(p.x + sc.rnd.nextInt(6, 40), p.y + sc.rnd.nextInt(6, 40)), c, -1)
        }
        return m
    }

    /** A plain print, almost all sky: too few distinct points, so the shift-only fallback lines it up. */
    @Test
    fun aPlainPrintStillLinesUp() {
        val sc = Scenes(8)
        val a = sc.photo(900, 600, color = true)
        val m = Imgproc.getRotationMatrix2D(org.opencv.core.Point(0.0, 0.0), 0.0, 1.0).also { it.put(0, 2, 3.0); it.put(1, 2, -2.0) }
        val b = Mat().also { Imgproc.warpAffine(a, it, m, a.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE) }
        val back = Deglare.align(a, b)!!
        val d = Mat()
        Core.absdiff(back.submat(20, 580, 20, 880), a.submat(20, 580, 20, 880), d)
        val err = Core.mean(d).`val`.take(3).average()
        val before = Mat().also { Core.absdiff(b.submat(20, 580, 20, 880), a.submat(20, 580, 20, 880), it) }.let { Core.mean(it).`val`.take(3).average() }
        // The best possible: shifting it back exactly (interpolation softens the noise a little either way).
        val inv = Imgproc.getRotationMatrix2D(org.opencv.core.Point(0.0, 0.0), 0.0, 1.0).also { it.put(0, 2, -3.0); it.put(1, 2, 2.0) }
        val ideal = Mat().also { Imgproc.warpAffine(b, it, inv, a.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE) }
            .let { m -> Mat().also { Core.absdiff(m.submat(20, 580, 20, 880), a.submat(20, 580, 20, 880), it) } }.let { Core.mean(it).`val`.take(3).average() }
        println("plain print: off by ${"%.2f".format(before)}, lined up ${"%.2f".format(err)}, best possible ${"%.2f".format(ideal)}")
        assertTrue("$before -> $err (ideal $ideal)", err < ideal + 0.5)
    }

    @Test
    fun aShotOfSomethingElseIsLeftOut() {
        val sc = Scenes(4)
        val a = detailed(sc, 600, 400)
        val b = detailed(sc, 600, 400)
        assertTrue(Deglare.align(a, b).also { it?.release() } == null)
    }

    @Test
    fun glareShareSeesBlownOutSpotsButNotAWhiteBorder() {
        val sc = Scenes(6)
        val bordered = sc.photo(600, 400, color = true, border = 30)
        assertTrue(Deglare.glareShare(bordered) < 0.002f)
        sc.glare(bordered, Pt(300f, 200f), 80.0, 50.0, 0.0, 255.0)
        assertTrue(Deglare.glareShare(bordered) > 0.01f)
    }

    @Test
    fun aPrintsOutlineCarriesOverToAnotherShot() {
        val from = Quad(Pt(100f, 100f), Pt(500f, 110f), Pt(490f, 400f), Pt(95f, 390f))
        val to = from.map { Pt(it.x * 1.1f + 20f, it.y * 0.95f - 10f) }
        val q = Quad(Pt(110f, 105f), Pt(480f, 115f), Pt(470f, 380f), Pt(105f, 385f))
        val moved = Deglare.mapQuad(q, from, to)
        assertTrue(moved.maxCornerShift(q.map { Pt(it.x * 1.1f + 20f, it.y * 0.95f - 10f) }) < 0.05f)
    }
}
