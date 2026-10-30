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
    fun doubleEdgeContoursBecomeOnePrint() {
        val outer = rect(0f, 0f, 101f, 101f)
        val inner = rect(1f, 1f, 99f, 99f)
        val result = Candidates.resolve(listOf(inner, outer))
        assertEquals(1, result.size)
        assertTrue(result[0].tl.x in 0f..1f)
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
    fun twoRoughOutlinesOfOneImageAreaDontMakeAPage() {
        val print = rect(0f, 0f, 200f, 240f)
        val imageArea = rect(12f, 12f, 176f, 176f)
        val roughImageArea = rect(14f, 10f, 174f, 180f)
        val result = Candidates.resolve(listOf(print, imageArea, roughImageArea))
        assertEquals(listOf(print), result)
    }

    @Test
    fun aPhotoWithRectanglesInItsPictureStaysOnePrint() {
        val print = rect(0f, 0f, 300f, 400f)
        val door = rect(40f, 120f, 60f, 100f)
        val window = rect(180f, 60f, 70f, 60f)
        // The caller looked between them and saw picture, not plain page.
        val result = Candidates.resolveScored(listOf(print, door, window).map { Candidates.Scored(it, 1f) }) { _, _ -> false }
        assertEquals(listOf(print), result)
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
}
