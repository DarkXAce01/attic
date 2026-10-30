package io.github.darkxace01.attic.vision

/**
 * Follows prints across camera frames and decides, firmly, when each one is ready.
 *
 * Detection on a single frame is noisy: corners jitter by a few pixels, a print is found on one frame and
 * the sleeve pocket around it on the next, and now and then a frame misses a print entirely. So:
 *  - a print only appears once it's been found on most recent frames (no ghosts, no outlines that only
 *    turn up now and then),
 *  - its outline is the per-corner median of recent detections, so a minority of frames that catch the
 *    pocket or the image area instead can't move it,
 *  - it survives [keepMissing] missed frames (no blinking),
 *  - when two tracks overlap, only the one found more often shows,
 *  - it locks only when it's still and the frames clearly agree on one outline; once locked, the outline
 *    freezes and only unlocks if the print really moves (hysteresis).
 *
 * Works in normalized 0..1 frame coordinates.
 */
class QuadTracker(
    private val confirmFrames: Int = 4,
    /** Share of recent frames a print must be found on to show. */
    private val showRate: Float = 0.6f,
    private val keepMissing: Int = 8,
    private val historySize: Int = 9,
    /** Corner spread (median distance from the median corner) under which a print counts as still. */
    private val stillSpread: Float = 0.006f,
    /** Share of recent detections that must agree on one outline before it can lock. */
    private val lockAgreement: Float = 0.6f,
    /** How far a locked print's outline may drift before it unlocks. */
    private val unlockShift: Float = 0.03f,
    /** How long a print has to stay still before it locks. */
    private val lockAfterMs: Long = 350,
) {
    class Track(val id: Long, first: Quad, val bornAt: Long) {
        internal val history = ArrayDeque<Quad>().apply { add(first) }
        /** Found or not, over the last [WINDOW] frames. */
        internal val hits = ArrayDeque<Boolean>().apply { add(true) }
        var quad: Quad = first
            internal set
        var seen: Int = 1
            internal set
        var missing: Int = 0
            internal set
        var locked: Boolean = false
            internal set
        internal var agreement = 1f
        internal var stillSince: Long = -1
        internal var lockedQuad: Quad? = null
        internal var confirmed = false

        internal val hitRate get() = hits.count { it }.toFloat() / hits.size

        /** What to draw and capture: frozen while locked, otherwise what recent frames agree on. */
        val shown: Quad get() = lockedQuad ?: quad
    }

    private var nextId = 1L
    private val tracks = mutableListOf<Track>()

    /**
     * Prints worth showing: confirmed, not gone for long, and not overlapping a track that's found more
     * often. Oldest first, so the order is stable.
     */
    var visible: List<Track> = emptyList()
        private set

    fun reset() {
        tracks.clear()
        visible = emptyList()
    }

    fun update(detections: List<Quad>, now: Long): List<Track> {
        // Each detection goes to the track it matches best, best matches first, so a print and the pocket
        // around it don't both claim the same detection.
        val pairs = mutableListOf<Triple<Track, Quad, Float>>()
        for (t in tracks) {
            val ref = t.shown
            for (d in detections) {
                val iou = d.iou(ref)
                // Centre distance as a fallback for small prints that shift a lot.
                val match = if (iou >= 0.5f) iou else if (d.center.dist(ref.center) < 0.06f) 0.5f * (1 - d.center.dist(ref.center) / 0.06f) else continue
                pairs += Triple(t, d, match)
            }
        }
        val matched = HashMap<Track, Quad>()
        val used = HashSet<Quad>()
        for ((t, d, _) in pairs.sortedByDescending { it.third }) {
            if (t in matched || d in used) continue
            matched[t] = d
            used += d
        }
        for (t in tracks) {
            val d = matched[t]
            t.hits.addLast(d != null)
            if (t.hits.size > WINDOW) t.hits.removeFirst()
            if (d != null) {
                t.history.addLast(d)
                if (t.history.size > historySize) t.history.removeFirst()
                agree(t)
                t.seen++
                t.missing = 0
            } else {
                t.missing++
            }
        }
        tracks.removeAll { it.missing > keepMissing }
        for (q in detections) if (q !in used) tracks += Track(nextId++, q, now)

        for (t in tracks) {
            if (!t.confirmed && t.seen >= confirmFrames && t.hitRate >= showRate) t.confirmed = true
            // A track that's only found now and then (a pocket outline alternating with its print) drops out.
            if (t.confirmed && t.hits.size >= WINDOW && t.hitRate < 0.3f) t.confirmed = false
            updateLock(t, now)
        }

        // Overlapping tracks are rival readings of the same print: show the one found more often.
        val shown = mutableListOf<Track>()
        val ranked = tracks.filter { it.confirmed }
            .sortedWith(compareByDescending<Track> { it.locked }.thenByDescending { it.hits.count { h -> h } }.thenBy { it.id })
        for (t in ranked) {
            val q = t.shown
            if (shown.none { it.shown.intersectionArea(q) >= minOf(it.shown.area, q.area) * 0.15f }) shown += t
        }
        visible = shown.sortedBy { it.id }
        return visible
    }

    /**
     * The outline is the per-corner median of recent detections: as long as most frames agree, the odd
     * frame that catches the pocket or the image area instead (or one corner on the wrong edge) can't move
     * it. Agreement is the share of detections whose corners sit close to it.
     */
    private fun agree(t: Track) {
        val m = medianQuad(t.history)
        t.quad = m
        // Corners go astray independently (one frame snaps a side to a neighbouring edge), so each corner
        // gets its own vote and the weakest one counts.
        t.agreement = (0 until 4).minOf { i -> t.history.count { it.points[i].dist(m.points[i]) <= SAME_OUTLINE } }.toFloat() / t.history.size
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
        val still = t.missing == 0 && t.history.size >= 4 && t.agreement >= lockAgreement && spread(t.history) < stillSpread
        if (!still) {
            t.stillSince = -1
            return
        }
        if (t.stillSince < 0) t.stillSince = now
        if (t.confirmed && now - t.stillSince >= lockAfterMs) {
            t.lockedQuad = t.quad
            t.locked = true
        }
    }

    companion object {
        /** Window for the hit rate, in frames. */
        private const val WINDOW = 10

        /** A detected corner this close to the agreed one agrees with it. */
        private const val SAME_OUTLINE = 0.01f

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
