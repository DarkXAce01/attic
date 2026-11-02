package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.PrintNet
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
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
}
