package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.PageLayout
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assert.assertEquals
import org.junit.Test

class PageLayoutTest {
    private fun rect(x: Float, y: Float, w: Float, h: Float) = Quad(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))

    @Test
    fun thePageIsThePrintsWithAMarginInsideThePhoto() {
        val f = PageLayout.frame(listOf(rect(100f, 200f, 300f, 200f), rect(500f, 250f, 200f, 300f)), 1000f, 1000f, margin = 0.1f)
        // Prints span 100..700 x 200..550: 600 wide, so a 60 px margin.
        assertEquals(PageLayout.Frame(40f, 140f, 760f, 610f), f)
        // Near the photo's edge the page stops at the edge.
        val g = PageLayout.frame(listOf(rect(10f, 10f, 500f, 500f)), 600f, 600f, margin = 0.1f)
        assertEquals(PageLayout.Frame(0f, 0f, 560f, 560f), g)
    }

    @Test
    fun aPrintSitsWhereItWas() {
        val frame = PageLayout.Frame(0f, 0f, 1000f, 500f)
        val s = PageLayout.spot(rect(100f, 100f, 200f, 100f), frame)
        assertEquals(0.2f, s.cx, 1e-4f)
        assertEquals(0.3f, s.cy, 1e-4f)
        assertEquals(0.2f, s.w, 1e-4f)
        assertEquals(0.2f, s.h, 1e-4f)
        assertEquals(0f, s.angleDeg, 1e-4f)
    }

    @Test
    fun aTiltedPrintKeepsItsTilt() {
        // A 100 x 50 print turned 30 degrees clockwise about (500, 500).
        val a = Math.toRadians(30.0)
        fun turn(x: Float, y: Float) = Pt((500 + x * Math.cos(a) - y * Math.sin(a)).toFloat(), (500 + x * Math.sin(a) + y * Math.cos(a)).toFloat())
        val q = Quad(turn(-50f, -25f), turn(50f, -25f), turn(50f, 25f), turn(-50f, 25f))
        val s = PageLayout.spot(q, PageLayout.Frame(0f, 0f, 1000f, 1000f))
        assertEquals(30f, s.angleDeg, 0.01f)
        assertEquals(0.1f, s.w, 1e-3f)
        assertEquals(0.05f, s.h, 1e-3f)
        assertEquals(0.5f, s.cx, 1e-3f)
    }
}
