package io.github.darkxace01.album

import io.github.darkxace01.album.vision.PrintDetector
import io.github.darkxace01.album.vision.Pt
import io.github.darkxace01.album.vision.Quad
import io.github.darkxace01.album.vision.Rectify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Runs the real detector on synthetic camera frames: prints with photo-like content, turned and seen
 * in perspective, on tables and album pages, with blur and sensor noise. Overlays of what was found are
 * written to app/build/detector/ for eyeballing.
 */
class DetectorTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        private const val W = 960
        private const val H = 1280
        private val out = File("build/detector").apply { mkdirs() }
    }

    private val rnd = Random(7)

    @Test
    fun singlePrintOnDarkTable() {
        val scene = background(Scalar(52.0, 40.0, 33.0, 255.0), grain = 14.0)
        val truth = place(scene, photo(600, 420, color = true), center = Pt(480f, 620f), angleDeg = 8f, tilt = 0.06f)
        check("single_dark_table", scene, listOf(truth))
    }

    @Test
    fun fourPrintsOnAnAlbumPage() {
        val scene = background(Scalar(40.0, 40.0, 42.0, 255.0), grain = 10.0)
        val page = Quad(Pt(40f, 70f), Pt(920f, 50f), Pt(935f, 1220f), Pt(30f, 1235f))
        fillQuad(scene, page, Scalar(176.0, 168.0, 150.0, 255.0), grain = 8.0)
        val truths = listOf(
            place(scene, photo(340, 250, true), Pt(270f, 330f), angleDeg = -3f),
            place(scene, photo(340, 250, true), Pt(680f, 340f), angleDeg = 4f),
            place(scene, photo(250, 340, true), Pt(250f, 850f), angleDeg = 2f),
            place(scene, photo(340, 250, false), Pt(680f, 860f), angleDeg = -6f),
        )
        check("album_page_four", scene, truths)
    }

    @Test
    fun whiteBorderedPrintsOnPalePage() {
        val scene = background(Scalar(200.0, 192.0, 175.0, 255.0), grain = 6.0)
        val truths = listOf(
            place(scene, photo(380, 300, true, border = 22), Pt(480f, 360f), angleDeg = 3f),
            place(scene, photo(380, 300, false, border = 22), Pt(480f, 900f), angleDeg = -4f),
        )
        check("bordered_pale_page", scene, truths)
    }

    @Test
    fun captureRefinementLandsOnTheRealEdge() {
        val scene = background(Scalar(30.0, 30.0, 30.0, 255.0), grain = 8.0)
        val truth = place(scene, photo(600, 400, true), Pt(480f, 640f), angleDeg = 12f, tilt = 0.08f)
        val camera = Mat()
        Imgproc.GaussianBlur(scene, camera, Size(3.0, 3.0), 0.8)
        addNoise(camera, 6.0)

        val detector = PrintDetector()
        val rough = detector.detect(camera).single()
        val refined = detector.refine(camera, rough)
        val err = refined.maxCornerShift(truth)
        assertTrue("refined corner error ${"%.2f".format(err)}px (rough ${"%.2f".format(rough.maxCornerShift(truth))}px)", err < 1.5f)

        val flat = Rectify.warp(camera, refined)
        assertEquals(1.5, flat.cols().toDouble() / flat.rows(), 0.03)
        assertTrue(Rectify.sharpness(flat) > 0)
    }

    @Test
    fun refinementRecoversFromAPoorPreviewGuess() {
        val scene = background(Scalar(52.0, 40.0, 33.0, 255.0), grain = 14.0)
        val truth = place(scene, photo(500, 700, true), Pt(480f, 640f), angleDeg = -5f)
        // The live preview runs on a small frame; simulate an outline that's off by ~1.5%.
        val guess = truth.map { Pt(it.x + 9f, it.y - 7f) }
        val refined = PrintDetector().refine(scene, guess)
        val err = refined.maxCornerShift(truth)
        assertTrue("corner error ${"%.2f".format(err)}px", err < 1.5f)
    }

    // ---- checking ----

    private fun check(name: String, scene: Mat, truths: List<Quad>) {
        val camera = Mat()
        Imgproc.GaussianBlur(scene, camera, Size(3.0, 3.0), 0.8)
        addNoise(camera, 6.0)

        val found = PrintDetector().detect(camera)
        writeOverlay(name, camera, truths, found)

        assertEquals("$name: number of prints found", truths.size, found.size)
        for (t in truths) {
            val match = found.minBy { it.center.dist(t.center) }
            val diag = hypot(t.outputWidth, t.outputHeight)
            val err = match.maxCornerShift(t)
            assertTrue("$name: corner error ${"%.1f".format(err)}px on a ${diag.toInt()}px print", err < diag * 0.02f)
        }
    }

    private fun writeOverlay(name: String, img: Mat, truths: List<Quad>, found: List<Quad>) {
        val bgr = Mat()
        Imgproc.cvtColor(img, bgr, Imgproc.COLOR_RGBA2BGR)
        fun draw(q: Quad, color: Scalar, t: Int) = Imgproc.polylines(
            bgr, listOf(MatOfPoint(*q.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), true, color, t,
        )
        truths.forEach { draw(it, Scalar(0.0, 255.0, 0.0), 1) }
        found.forEach { draw(it, Scalar(0.0, 0.0, 255.0), 2) }
        Imgcodecs.imwrite(File(out, "$name.png").path, bgr)
    }

    // ---- scene building ----

    private fun background(color: Scalar, grain: Double): Mat {
        val m = Mat(H, W, CvType.CV_8UC4, color)
        addNoise(m, grain, blur = 9.0)
        return m
    }

    /** Something photo-like: a sky-to-ground gradient, a few shapes, noise. Optional white border. */
    private fun photo(w: Int, h: Int, color: Boolean, border: Int = 0): Mat {
        val m = Mat(h, w, CvType.CV_8UC4, Scalar(245.0, 243.0, 238.0, 255.0))
        val iw = w - border * 2
        val ih = h - border * 2
        val img = m.submat(border, border + ih, border, border + iw)
        val top = if (color) doubleArrayOf(120.0, 160.0, 210.0) else doubleArrayOf(170.0, 170.0, 170.0)
        val bottom = if (color) doubleArrayOf(70.0, 95.0, 50.0) else doubleArrayOf(60.0, 60.0, 60.0)
        for (y in 0 until ih) {
            val t = y.toDouble() / ih
            Imgproc.line(
                img, Point(0.0, y.toDouble()), Point(iw.toDouble(), y.toDouble()),
                Scalar(top[0] + (bottom[0] - top[0]) * t, top[1] + (bottom[1] - top[1]) * t, top[2] + (bottom[2] - top[2]) * t, 255.0),
            )
        }
        repeat(4) {
            val c = Scalar(rnd.nextDouble(30.0, 230.0), rnd.nextDouble(30.0, 230.0), rnd.nextDouble(30.0, 230.0), 255.0)
            val x = rnd.nextInt(iw / 6, iw * 5 / 6).toDouble()
            val y = rnd.nextInt(ih / 6, ih * 5 / 6).toDouble()
            if (it % 2 == 0) {
                Imgproc.circle(img, Point(x, y), rnd.nextInt(10, ih / 5), c, -1)
            } else {
                Imgproc.rectangle(img, Point(x, y), Point(x + iw / 6, y + ih / 5), c, -1)
            }
        }
        addNoise(img, 5.0)
        return m
    }

    /** Pastes [print] into [scene] turned by [angleDeg] and leaning back by [tilt]. Returns where it landed. */
    private fun place(scene: Mat, print: Mat, center: Pt, angleDeg: Float, tilt: Float = 0.03f): Quad {
        val hw = print.cols() / 2f
        val hh = print.rows() / 2f
        val a = Math.toRadians(angleDeg.toDouble())
        fun turn(x: Float, y: Float): Pt {
            val rx = (x * cos(a) - y * sin(a)).toFloat()
            val ry = (x * sin(a) + y * cos(a)).toFloat()
            return Pt(center.x + rx, center.y + ry)
        }
        // Perspective: the far (top) edge is a little narrower.
        val quad = Quad(
            turn(-hw * (1 - tilt), -hh), turn(hw * (1 - tilt), -hh),
            turn(hw, hh), turn(-hw, hh),
        )
        val from = MatOfPoint2f(
            Point(0.0, 0.0), Point(print.cols().toDouble(), 0.0),
            Point(print.cols().toDouble(), print.rows().toDouble()), Point(0.0, print.rows().toDouble()),
        )
        val to = MatOfPoint2f(*quad.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val m = Imgproc.getPerspectiveTransform(from, to)
        val warped = Mat()
        Imgproc.warpPerspective(print, warped, m, scene.size(), Imgproc.INTER_LINEAR)
        val mask = Mat(scene.size(), CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(mask, listOf(MatOfPoint(*quad.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), Scalar(255.0))
        warped.copyTo(scene, mask)
        return quad
    }

    private fun fillQuad(scene: Mat, q: Quad, color: Scalar, grain: Double) {
        val layer = Mat(scene.size(), scene.type(), color)
        addNoise(layer, grain, blur = 5.0)
        val mask = Mat(scene.size(), CvType.CV_8UC1, Scalar(0.0))
        Imgproc.fillPoly(mask, listOf(MatOfPoint(*q.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())), Scalar(255.0))
        layer.copyTo(scene, mask)
    }

    private fun addNoise(m: Mat, sigma: Double, blur: Double = 0.0) {
        val noise = Mat(m.size(), CvType.CV_16SC4)
        Core.randn(noise, 0.0, sigma)
        if (blur > 0) Imgproc.GaussianBlur(noise, noise, Size(0.0, 0.0), blur / 3)
        val wide = Mat()
        m.convertTo(wide, CvType.CV_16SC4)
        Core.add(wide, noise, wide)
        wide.convertTo(m, m.type())
    }
}
