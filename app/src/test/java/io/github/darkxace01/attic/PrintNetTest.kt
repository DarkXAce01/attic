package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.EdgeFit
import io.github.darkxace01.attic.vision.EdgeSnap
import io.github.darkxace01.attic.vision.PrintNet
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File

class PrintNetTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()

        val net by lazy { PrintNet(File("src/main/assets/printnet.onnx").readBytes()) }
    }

    @Test
    fun theModelLoadsAndRunsOnAFrame() {
        val scene = Scenes(42).random()
        val t0 = System.nanoTime()
        net.detect(scene.image)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("one frame: $ms ms")
        val maps = net.lastMaps!!
        assertEquals(3, maps.size)
        assertTrue(maps.all { m -> m.size == PrintNet.W * PrintNet.H && m.all { it in 0f..1f } })
        scene.image.release()
    }

    /** Maps drawn by hand: two prints, one cut off by the frame. Only the whole one is an outline. */
    @Test
    fun outlinesComeFromCoresAndSidesFromThePrintMap() {
        val w = PrintNet.W
        val h = PrintNet.H
        val print = FloatArray(w * h)
        val core = FloatArray(w * h)
        fun paint(m: FloatArray, q: Quad) {
            for (y in 0 until h) for (x in 0 until w) if (q.contains(Pt(x + 0.5f, y + 0.5f))) m[y * w + x] = 1f
        }
        fun shrink(q: Quad, s: Float) = q.center.let { c -> q.map { Pt(c.x + (it.x - c.x) * s, c.y + (it.y - c.y) * s) } }
        val whole = Quad(Pt(40f, 50f), Pt(200f, 56f), Pt(196f, 170f), Pt(36f, 164f))
        val cut = Quad(Pt(-30f, 220f), Pt(120f, 220f), Pt(120f, 330f), Pt(-30f, 330f))
        for (q in listOf(whole, cut)) {
            paint(print, q)
            paint(core, shrink(q, PrintNet.CORE))
        }
        val found = PrintNet.quads(arrayOf(print, core, FloatArray(w * h)))
        assertEquals(1, found.size)
        assertTrue("corners ${found[0]}", found[0].maxCornerShift(whole) < 1.5f)
    }

    /**
     * At capture, taking the network's close look at each print, when it sits on stronger edges, never
     * makes outlines worse than fitting the edges of the whole-frame outline alone (on real album pages it
     * makes them tighter: see RealPhotos). Scenes are scaled up 3x to stand in for the full-resolution photo.
     */
    @Test
    fun theCloseLookNeverLoosensCaptureOutlines() {
        val scenes = Scenes(7)
        var before = 0.0
        var after = 0.0
        var n = 0
        repeat(12) {
            val scene = scenes.random()
            val full = Mat()
            Imgproc.resize(scene.image, full, org.opencv.core.Size(), 3.0, 3.0, Imgproc.INTER_CUBIC)
            for (rough in net.detect(scene.image)) {
                val t = scene.truths.maxByOrNull { it.iou(rough) }?.takeIf { it.iou(rough) > 0.8f } ?: continue
                val guess = rough.scale(3f, 3f)
                val truth = t.scale(3f, 3f)
                before += EdgeSnap.snap(full, guess, (EdgeFit.diagonal(guess) * 0.012f).coerceAtLeast(4f)).iou(truth)
                val a = net.finalOutline(full, guess).iou(truth)
                val b0 = EdgeSnap.snap(full, guess, (EdgeFit.diagonal(guess) * 0.012f).coerceAtLeast(4f)).iou(truth)
                val r = net.refineAt(full, guess)
                after += a
                n++
            }
            listOf(scene.image, full).forEach { it.release() }
        }
        println("capture IoU over $n prints: edges only ${"%.4f".format(before / n)}, with close look ${"%.4f".format(after / n)}")
        assertTrue(n > 15)
        assertTrue(after / n >= before / n - 0.002)
    }

    /** The back of a print: plain paper, a few pen strokes, on a table. Its outline must still be found. */
    @Test
    fun theBackOfAPrintIsFound() {
        val sc = Scenes(21)
        var found = 0
        val tables = listOf(org.opencv.core.Scalar(40.0, 34.0, 30.0, 255.0), org.opencv.core.Scalar(120.0, 85.0, 60.0, 255.0), org.opencv.core.Scalar(150.0, 150.0, 150.0, 255.0), org.opencv.core.Scalar(200.0, 195.0, 185.0, 255.0))
        for ((i, table) in tables.withIndex()) {
            val scene = sc.background(960, 1280, table, grain = 8.0)
            val back = org.opencv.core.Mat(420, 600, org.opencv.core.CvType.CV_8UC4, org.opencv.core.Scalar(236.0, 233.0, 225.0, 255.0))
            repeat(6) { k ->
                val y = 60.0 + k * 22
                Imgproc.line(back, org.opencv.core.Point(60.0, y), org.opencv.core.Point(60.0 + sc.rnd.nextDouble(120.0, 380.0), y + sc.rnd.nextDouble(-4.0, 4.0)), org.opencv.core.Scalar(60.0, 60.0, 140.0, 255.0), 2)
            }
            val truth = sc.place(scene, back, io.github.darkxace01.attic.vision.Pt(480f, 640f), angleDeg = 8f * i - 10f, tilt = 0.05f)
            val shot = sc.camera(scene)
            val all = net.detect(shot)
            val q = all.maxByOrNull { it.area }
            val iou = q?.iou(truth) ?: 0f
            println("  ${all.size} found, areas ${all.map { (it.area / truth.area * 100).toInt() }}% of the back, IoUs ${all.map { "%.2f".format(it.iou(truth)) }}")
            println("back on table ${table.`val`[0].toInt()}: IoU ${"%.3f".format(iou)}")
            if (iou >= 0.9f) found++
            shot.release()
        }
        println("backs found: $found of ${tables.size}")
        assertTrue("backs found: $found of ${tables.size}", found >= 3)
    }
}
