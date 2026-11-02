package io.github.darkxace01.attic

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

    private fun jitter(q: Quad, r: kotlin.random.Random, amount: Float) =
        q.map { Pt(it.x + (r.nextFloat() - 0.5f) * 2 * amount, it.y + (r.nextFloat() - 0.5f) * 2 * amount) }

    @Test
    fun aOneFrameGhostNeverShows() {
        val tracker = QuadTracker()
        tracker.update(listOf(rect(0.2f, 0.2f, 0.3f, 0.3f)), 0)
        tracker.update(emptyList(), 33)
        assertTrue(tracker.visible.isEmpty())
    }

    @Test
    fun jitteryDetectionsStillLock() {
        // Real detections wobble by a few pixels every frame; that must not stop the lock.
        val tracker = QuadTracker(lockAfterMs = 300)
        val q = rect(0.2f, 0.2f, 0.5f, 0.4f)
        val r = kotlin.random.Random(1)
        var t = 0L
        repeat(20) { tracker.update(listOf(jitter(q, r, 0.004f)), t); t += 33 }
        assertTrue(tracker.visible.single().locked)
    }

    @Test
    fun lockedOutlineStaysFrozenThroughJitter() {
        val tracker = QuadTracker(lockAfterMs = 200)
        val q = rect(0.2f, 0.2f, 0.5f, 0.4f)
        val r = kotlin.random.Random(2)
        var t = 0L
        repeat(15) { tracker.update(listOf(jitter(q, r, 0.003f)), t); t += 33 }
        val frozen = tracker.visible.single().shown
        repeat(15) { tracker.update(listOf(jitter(q, r, 0.008f)), t); t += 33 }
        assertEquals(frozen, tracker.visible.single().shown)
        assertTrue(tracker.visible.single().locked)
    }

    @Test
    fun oneBadFrameDoesntMoveTheOutline() {
        val tracker = QuadTracker()
        val q = rect(0.2f, 0.2f, 0.5f, 0.4f)
        var t = 0L
        repeat(6) { tracker.update(listOf(q), t); t += 33 }
        // One frame catches the print's inner image area instead of its edge.
        tracker.update(listOf(rect(0.23f, 0.23f, 0.44f, 0.34f)), t)
        assertTrue(tracker.visible.single().shown.maxCornerShift(q) < 0.001f)
    }

    @Test
    fun reallyMovingUnlocks() {
        val tracker = QuadTracker(lockAfterMs = 200)
        var t = 0L
        repeat(12) { tracker.update(listOf(rect(0.2f, 0.2f, 0.5f, 0.4f)), t); t += 33 }
        assertTrue(tracker.visible.single().locked)
        repeat(6) { tracker.update(listOf(rect(0.26f, 0.24f, 0.5f, 0.4f)), t); t += 33 }
        assertFalse(tracker.visible.single().locked)
    }

    @Test
    fun droppedFramesDontBlink() {
        val tracker = QuadTracker()
        val q = rect(0.2f, 0.2f, 0.5f, 0.4f)
        var t = 0L
        repeat(5) { tracker.update(listOf(q), t); t += 33 }
        val id = tracker.visible.single().id
        repeat(5) { tracker.update(emptyList(), t); t += 33 }
        assertEquals(id, tracker.visible.single().id)
        tracker.update(listOf(q), t)
        assertEquals(id, tracker.visible.single().id)
    }

    @Test
    fun aPrintAndItsPocketDontTakeTurns() {
        // Frames find the print most of the time and the sleeve pocket around it otherwise. One outline
        // shows, the print's, and it doesn't swing between the two.
        val tracker = QuadTracker(lockAfterMs = 200)
        val print = rect(0.2f, 0.2f, 0.5f, 0.4f)
        val pocket = rect(0.17f, 0.17f, 0.56f, 0.46f)
        var t = 0L
        val shownEach = mutableListOf<Quad>()
        repeat(30) { i ->
            tracker.update(listOf(if (i % 3 == 2) pocket else print), t)
            t += 33
            tracker.visible.forEach { shownEach += it.shown }
            assertTrue("frame $i shows ${tracker.visible.size}", tracker.visible.size <= 1)
        }
        assertTrue(tracker.visible.single().locked)
        assertTrue(tracker.visible.single().shown.maxCornerShift(print) < 0.001f)
        assertTrue(shownEach.drop(4).all { it.maxCornerShift(print) < 0.001f })
    }

    @Test
    fun anOutlineFoundNowAndThenNeverShows() {
        val tracker = QuadTracker()
        val print = rect(0.2f, 0.2f, 0.5f, 0.4f)
        val ghost = rect(0.6f, 0.6f, 0.3f, 0.3f)
        var t = 0L
        repeat(40) { i ->
            tracker.update(if (i % 3 == 0) listOf(print, ghost) else listOf(print), t)
            t += 33
        }
        assertEquals(1, tracker.visible.size)
    }
}
