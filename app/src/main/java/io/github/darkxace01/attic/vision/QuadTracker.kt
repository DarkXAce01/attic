package io.github.darkxace01.attic.vision

/**
 * Follows prints across camera frames and decides, firmly, when each one is ready.
 *
 * Detection on a single frame is noisy: corners jitter by a few pixels, a print's border is found on
 * one frame and its image area on the next, and now and then a frame misses a print entirely. So:
 *  - a print only appears after it's been seen on [confirmFrames] frames (no one-frame ghosts),
 *  - its outline is the per-corner median of the last few detections (one bad frame can't move it),
 *  - it survives [keepMissing] missed frames (no blinking),
 *  - once locked, the outline freezes and only unlocks if the print really moves (hysteresis).
 *
 * Works in normalized 0..1 frame coordinates.
 */
class QuadTracker(
    private val confirmFrames: Int = 3,
    private val keepMissing: Int = 8,
    private val historySize: Int = 7,
    /** Corner spread (median distance from the median corner) under which a print counts as still. */
    private val stillSpread: Float = 0.006f,
    /** How far a locked print's outline may drift before it unlocks. */
    private val unlockShift: Float = 0.03f,
    /** How long a print has to stay still before it locks. */
    private val lockAfterMs: Long = 350,
) {
    class Track(val id: Long, first: Quad, val bornAt: Long) {
        internal val history = ArrayDeque<Quad>().apply { add(first) }
        var quad: Quad = first
            internal set
        var seen: Int = 1
            internal set
        var missing: Int = 0
            internal set
        var locked: Boolean = false
            internal set
        internal var stillSince: Long = -1
        internal var lockedQuad: Quad? = null

        /** What to draw and capture: frozen while locked, otherwise the median of recent frames. */
        val shown: Quad get() = lockedQuad ?: quad
    }

    private var nextId = 1L
    private val tracks = mutableListOf<Track>()

    /** Prints worth showing: confirmed, and not gone for long. Oldest first, so the order is stable. */
    val visible: List<Track> get() = tracks.filter { it.seen >= confirmFrames }

    fun reset() = tracks.clear()

    fun update(detections: List<Quad>, now: Long): List<Track> {
        val unmatched = detections.toMutableList()
        // Best overlap first; centre distance as a fallback for small prints that shift a lot.
        for (t in tracks) {
            val ref = t.shown
            val best = unmatched.maxByOrNull { it.iou(ref) }
                ?.takeIf { it.iou(ref) >= 0.5f }
                ?: unmatched.minByOrNull { it.center.dist(ref.center) }?.takeIf { it.center.dist(ref.center) < 0.06f }
            if (best != null) {
                unmatched.remove(best)
                t.history.addLast(best)
                if (t.history.size > historySize) t.history.removeFirst()
                t.quad = medianQuad(t.history)
                t.seen++
                t.missing = 0
            } else {
                t.missing++
            }
        }
        tracks.removeAll { it.missing > keepMissing }
        for (q in unmatched) tracks += Track(nextId++, q, now)

        for (t in tracks) updateLock(t, now)
        return visible
    }

    private fun updateLock(t: Track, now: Long) {
        val locked = t.lockedQuad
        if (locked != null) {
            if (t.missing == 0 && t.quad.maxCornerShift(locked) > unlockShift) {
                t.lockedQuad = null
                t.locked = false
                t.stillSince = -1
            }
            return
        }
        val still = t.missing == 0 && t.history.size >= 4 && spread(t.history) < stillSpread
        if (!still) {
            t.stillSince = -1
            return
        }
        if (t.stillSince < 0) t.stillSince = now
        if (t.seen >= confirmFrames && now - t.stillSince >= lockAfterMs) {
            t.lockedQuad = t.quad
            t.locked = true
        }
    }

    companion object {
        fun medianQuad(qs: Collection<Quad>): Quad {
            fun med(values: List<Float>): Float = values.sorted().let { s ->
                if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
            }
            val corners = (0 until 4).map { i ->
                Pt(med(qs.map { it.points[i].x }), med(qs.map { it.points[i].y }))
            }
            return Quad(corners[0], corners[1], corners[2], corners[3])
        }

        /** Largest, over the four corners, of the median distance from that corner's median position. */
        fun spread(qs: Collection<Quad>): Float {
            val m = medianQuad(qs)
            return (0 until 4).maxOf { i ->
                val d = qs.map { it.points[i].dist(m.points[i]) }.sorted()
                d[d.size / 2]
            }
        }
    }
}
