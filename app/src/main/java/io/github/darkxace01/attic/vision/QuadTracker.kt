package io.github.darkxace01.attic.vision

/**
 * Follows prints across camera frames so the outlines don't flicker, and decides when each one is
 * steady enough to capture. Works in normalized 0..1 frame coordinates.
 */
class QuadTracker(
    /** Max centre distance for a detection to count as the same print as last frame. */
    private val matchDistance: Float = 0.08f,
    /** Corner movement per frame under which a print counts as still. */
    private val stillShift: Float = 0.012f,
    /** How long a print has to stay still to lock. */
    private val lockAfterMs: Long = 450,
    /** Frames a print may go undetected before its outline disappears. */
    private val keepMissing: Int = 4,
    /** Smoothing: weight of the newest detection. */
    private val alpha: Float = 0.45f,
) {
    class Track(val id: Long, var quad: Quad, var raw: Quad, val bornAt: Long) {
        var stillSince: Long = bornAt
        var missing: Int = 0
        var seen: Int = 1
        var locked: Boolean = false
    }

    private var nextId = 1L
    private val tracks = mutableListOf<Track>()

    val current: List<Track> get() = tracks.filter { it.missing == 0 }

    fun reset() {
        tracks.clear()
    }

    fun update(detections: List<Quad>, now: Long): List<Track> {
        val unmatched = detections.toMutableList()
        for (t in tracks.sortedByDescending { it.quad.area }) {
            val best = unmatched.minByOrNull { it.center.dist(t.quad.center) }
            if (best != null && best.center.dist(t.quad.center) < matchDistance) {
                unmatched.remove(best)
                val shift = best.maxCornerShift(t.raw)
                if (shift > stillShift) t.stillSince = now
                t.raw = best
                t.quad = t.quad.lerp(best, alpha)
                t.missing = 0
                t.seen++
            } else {
                t.missing++
            }
        }
        tracks.removeAll { it.missing > keepMissing }
        for (q in unmatched) tracks += Track(nextId++, q, q, now)
        for (t in tracks) t.locked = t.missing == 0 && t.seen >= 4 && now - t.stillSince >= lockAfterMs
        return current
    }
}
