package io.github.darkxace01.attic.vision

/**
 * Turns raw rectangle candidates into the set of prints.
 *
 * The same print is usually found several times (by different methods, or as the outer and inner boundary
 * of one edge line), album pages enclose the prints on them, and a print's image area sits inside its
 * white border. So:
 *  1. near-identical candidates collapse into the best-scoring one (equal scores: corners averaged),
 *  2. anything enclosing other candidates is a page, not a print, when [isPageAround] says so (by default:
 *     when it holds two or more separate ones). A photo can hold rectangles of its own (a window, a door),
 *     so the caller can look at whether the space between them is plain page or picture,
 *  3. prints in an album don't overlap. Something entirely inside another candidate (a print's image area
 *     inside its white border) loses to the outer one; partly overlapping candidates are decided by score.
 */
object Candidates {
    const val SAME_IOU = 0.93f

    class Scored(val quad: Quad, val score: Float)

    fun resolve(raw: List<Quad>): List<Quad> = resolveScored(raw.map { Scored(it, 1f) })

    fun resolveScored(raw: List<Scored>, isPageAround: (outer: Quad, inner: List<Quad>) -> Boolean = { _, inner -> inner.size >= 2 }): List<Quad> {
        val kept = mutableListOf<Scored>()
        for (c in raw.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.quad.area })) {
            val i = kept.indexOfFirst { it.quad.iou(c.quad) >= SAME_IOU }
            when {
                i < 0 -> kept += c
                kept[i].score - c.score < 0.05f -> kept[i] = Scored(Quad.average(kept[i].quad, c.quad), kept[i].score)
            }
        }
        val quads = kept.map { it.quad }
        val scoreOf = kept.associate { it.quad to it.score }

        fun encloses(outer: Quad, inner: Quad) =
            outer !== inner && inner.area < outer.area * 0.9f && inner.points.all { outer.contains(it) }

        // A single print can hold nested shapes (its image area, a window in the photo), so only the
        // outermost things inside count.
        fun isPage(c: Quad): Boolean {
            val inside = quads.filter { encloses(c, it) }
            val top = inside.filter { child -> inside.none { encloses(it, child) } }
            // Two rough outlines of the same thing overlap; separate prints don't. Count each overlapping
            // group once.
            val groups = mutableListOf<MutableList<Quad>>()
            for (q in top) {
                val g = groups.firstOrNull { g -> g.any { it.intersectionArea(q) >= minOf(it.area, q.area) * 0.5f } }
                if (g != null) g += q else groups += mutableListOf(q)
            }
            return groups.isNotEmpty() && isPageAround(c, groups.map { g -> g.maxBy { it.area } })
        }

        val remaining = quads.filterNot(::isPage)
        // Bigger first, so a print beats the shapes inside it.
        val result = mutableListOf<Quad>()
        for (q in remaining.sortedByDescending { it.area }) {
            val clash = remaining.firstOrNull { o ->
                o !== q && o.intersectionArea(q) >= minOf(o.area, q.area) * 0.4f && !q.points.all { o.contains(it) } && !o.points.all { q.contains(it) }
            }
            val beaten = clash != null && (scoreOf[clash] ?: 0f) > (scoreOf[q] ?: 0f) + 0.02f
            if (!beaten && result.none { it.intersectionArea(q) >= q.area * 0.4f }) result += q
        }
        return result
    }
}
