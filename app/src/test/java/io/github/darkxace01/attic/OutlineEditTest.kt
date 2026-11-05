package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.OutlineEdit
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OutlineEditTest {
    private val q = Quad(Pt(100f, 100f), Pt(300f, 100f), Pt(300f, 400f), Pt(100f, 400f))

    private fun near(a: Pt, b: Pt) {
        assertEquals(a.x, b.x, 0.01f)
        assertEquals(a.y, b.y, 0.01f)
    }

    @Test
    fun handlesAreTheCornersThenTheMiddlesOfTheSides() {
        val h = OutlineEdit.handles(q)
        assertEquals(8, h.size)
        near(Pt(200f, 100f), h[4]) // top
        near(Pt(300f, 250f), h[5]) // right
        near(Pt(100f, 250f), h[7]) // left
    }

    @Test
    fun aCornerFollowsTheFinger() {
        val out = OutlineEdit.drag(q, 2, Pt(15f, -10f), 1000f, 1000f)
        near(Pt(315f, 390f), out.br)
        near(q.tl, out.tl)
    }

    @Test
    fun aSideMovesStraightOutAndIgnoresSlidingAlongIt() {
        // The right side, dragged right and a long way down: only the rightward part counts.
        val out = OutlineEdit.drag(q, 5, Pt(20f, 80f), 1000f, 1000f)
        near(Pt(320f, 100f), out.tr)
        near(Pt(320f, 400f), out.br)
        near(q.tl, out.tl)
        near(q.bl, out.bl)
    }

    @Test
    fun aTiltedSideKeepsItsAngle() {
        val tilted = Quad(Pt(100f, 100f), Pt(300f, 120f), Pt(280f, 420f), Pt(80f, 400f))
        val out = OutlineEdit.drag(tilted, 5, Pt(30f, 0f), 1000f, 1000f)
        val before = tilted.br - tilted.tr
        val after = out.br - out.tr
        assertEquals(before.x, after.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
    }

    @Test
    fun outlinesStayInsideThePhoto() {
        val out = OutlineEdit.drag(q, 0, Pt(-500f, -500f), 1000f, 1000f)
        near(Pt(0f, 0f), out.tl)
    }

    @Test
    fun aDragThatWouldFoldTheOutlineIsRefused() {
        // The top-left corner pulled past the bottom-right one.
        assertSame(q, OutlineEdit.drag(q, 0, Pt(400f, 400f), 1000f, 1000f))
    }
}
