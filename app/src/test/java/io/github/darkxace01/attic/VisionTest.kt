package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.Candidates
import io.github.darkxace01.attic.vision.Pt
import io.github.darkxace01.attic.vision.Quad
import io.github.darkxace01.attic.vision.QuadTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTest {
    private fun rect(x: Float, y: Float, w: Float, h: Float) =
        Quad(Pt(x, y), Pt(x + w, y), Pt(x + w, y + h), Pt(x, y + h))

    @Test
    fun cornersAreOrderedWhateverTheInputOrder() {
        val expected = rect(10f, 20f, 100f, 60f)
        val shuffled = listOf(expected.br, expected.tl, expected.bl, expected.tr)
        assertEquals(expected, Quad.fromCorners(shuffled))
    }

    @Test
    fun slightlyTurnedPrintKeepsItsTopLeft() {
        // Rotated ~10° clockwise around its centre.
        val q = Quad.fromCorners(listOf(Pt(112f, 10f), Pt(205f, 27f), Pt(192f, 96f), Pt(98f, 80f)))
        assertEquals(Pt(112f, 10f), q.tl)
        assertEquals(Pt(205f, 27f), q.tr)
        assertEquals(Pt(192f, 96f), q.br)
    }

    @Test
    fun iouOfIdenticalAndDisjointQuads() {
        val a = rect(0f, 0f, 10f, 10f)
        assertEquals(1f, a.iou(a), 1e-4f)
        assertEquals(0f, a.iou(rect(20f, 20f, 5f, 5f)), 1e-4f)
        assertEquals(1f / 3f, a.iou(rect(5f, 0f, 10f, 10f)), 1e-3f)
    }

    @Test
    fun outputSizeUsesLongerOppositeSides() {
        val q = Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(90f, 50f), Pt(10f, 50f))
        assertEquals(100f, q.outputWidth, 1e-3f)
    }

    @Test
    fun skewedShapesAreRejected() {
        assertTrue(rect(0f, 0f, 10f, 10f).isReasonable())
        assertFalse(Quad(Pt(0f, 0f), Pt(100f, 0f), Pt(160f, 10f), Pt(0f, 12f)).isReasonable())
    }

    @Test
    fun doubleEdgeContoursMergeToTheMiddle() {
        val outer = rect(0f, 0f, 102f, 102f)
        val inner = rect(2f, 2f, 98f, 98f)
        val result = Candidates.resolve(listOf(inner, outer))
        assertEquals(1, result.size)
        assertEquals(1f, result[0].tl.x, 1e-3f)
    }

    @Test
    fun albumPageIsDroppedAndItsPrintsKept() {
        val page = rect(0f, 0f, 400f, 300f)
        val left = rect(20f, 20f, 160f, 120f)
        val right = rect(220f, 20f, 160f, 120f)
        val result = Candidates.resolve(listOf(page, left, right))
        assertEquals(setOf(left, right), result.toSet())
    }

    @Test
    fun bordersAndThingsInsideAPhotoDontBecomeExtraPrints() {
        val print = rect(0f, 0f, 200f, 240f)
        val imageArea = rect(12f, 12f, 176f, 176f)
        val windowInPhoto = rect(40f, 40f, 30f, 30f)
        val result = Candidates.resolve(listOf(print, imageArea, windowInPhoto))
        assertEquals(listOf(print), result)
    }

    @Test
    fun trackerLocksAfterHoldingStill() {
        val tracker = QuadTracker(lockAfterMs = 400)
        val q = rect(0.2f, 0.2f, 0.5f, 0.4f)
        var t = 0L
        repeat(6) { tracker.update(listOf(q), t); t += 50 }
        assertFalse(tracker.current.single().locked)
        repeat(6) { tracker.update(listOf(q), t); t += 50 }
        assertTrue(tracker.current.single().locked)
    }

    @Test
    fun movingResetsTheLock() {
        val tracker = QuadTracker(lockAfterMs = 200)
        var t = 0L
        repeat(10) { tracker.update(listOf(rect(0.2f, 0.2f, 0.5f, 0.4f)), t); t += 50 }
        assertTrue(tracker.current.single().locked)
        tracker.update(listOf(rect(0.25f, 0.22f, 0.5f, 0.4f)), t)
        assertFalse(tracker.current.single().locked)
    }

    @Test
    fun briefDropoutKeepsTheSameTrack() {
        val tracker = QuadTracker()
        val q = rect(0.2f, 0.2f, 0.5f, 0.4f)
        tracker.update(listOf(q), 0)
        val id = tracker.current.single().id
        tracker.update(emptyList(), 50)
        tracker.update(emptyList(), 100)
        tracker.update(listOf(q), 150)
        assertEquals(id, tracker.current.single().id)
    }
}
