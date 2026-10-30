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
 *  3. prints in an album don't overlap: the best-scoring outlines are kept and anything overlapping them
 *     goes. On equal scores the bigger one wins (a print over the shapes inside it).
 */
object Candidates {
    const val SAME_IOU = 0.93f

    class Scored(val quad: Quad, val score: Float)

    /** Test hook: why each outline was dropped. */
    internal var why: ((Quad, String) -> Unit)? = null

    fun resolve(raw: List<Quad>): List<Quad> = resolveScored(raw.map { Scored(it, 1f) })

    fun resolveScored(raw: List<Scored>, isPageAround: (outer: Quad, inner: List<Quad>) -> Boolean = { _, inner -> inner.size >= 2 }): List<Quad> {
        val kept = mutableListOf<Scored>()
        for (c in raw.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.quad.area })) {
            val i = kept.indexOfFirst { it.quad.iou(c.quad) >= SAME_IOU }
            when {
                i < 0 -> kept += c
                // Two traces of the very same edges: average out their jitter. Anything less alike is a
                // different outline (a pocket around the print) and the better one stands alone.
                kept[i].score - c.score < 0.05f && kept[i].quad.iou(c.quad) >= 0.97f ->
                    kept[i] = Scored(Quad.average(kept[i].quad, c.quad), kept[i].score)
            }
        }
        val quads = kept.map { it.quad }
        val scoreOf = kept.associate { it.quad to it.score }

        // Nearly all of it inside: an outline traced along a print's own edge may cut its corner by a pixel.
        fun encloses(outer: Quad, inner: Quad) =
            outer !== inner && inner.area < outer.area * 0.97f && outer.intersectionArea(inner) >= inner.area * 0.92f

        // Decided smallest first, so an outline already found to be a page (or a pocket, or an outline
        // straddling a print and the page edge) doesn't hide the prints inside it from bigger ones.
        val pages = HashSet<Quad>()

        // A single print can hold nested shapes (its image area, a window in the photo), so only the
        // outermost things inside count.
        fun isPage(c: Quad): Boolean {
            val inside = quads.filter { it !in pages && encloses(c, it) }
            val top = inside.filter { child -> inside.none { encloses(it, child) } }
            // Two rough outlines of the same thing overlap; separate prints don't. Count each overlapping
            // group once.
            val groups = mutableListOf<MutableList<Quad>>()
            for (q in top) {
                val g = groups.firstOrNull { g -> g.any { it.intersectionArea(q) >= minOf(it.area, q.area) * 0.5f } }
                if (g != null) g += q else groups += mutableListOf(q)
            }
            if (groups.isEmpty()) return false
            val reps = groups.map { g -> g.maxBy { scoreOf[it] ?: 0f } }
            // One thing well inside: a page with one print only if that print is about as strong an outline
            // as the page; a weak one is a shape in the photo's picture.
            if (reps.size == 1 && reps[0].area < c.area * 0.6f && (scoreOf[reps[0]] ?: 0f) < (scoreOf[c] ?: 0f) - 0.12f) return false
            if (reps.size == 1) return isPageAround(c, reps).also { if (it) why?.invoke(c, "page: one inside, ${"%.2f".format(reps[0].area / c.area)} of it, scores ${scoreOf[c]} vs ${scoreOf[reps[0]]}") }
            // Several things inside. Prints on a page are big and alike in size, and strong outlines
            // themselves; the rectangles in a photo's picture (windows, blinds, a door) are small, mixed
            // and weaker.
            val big = reps.filter { it.area >= c.area * 0.04f }
            if (big.size < 2 || big.minOf { it.area } < big.maxOf { it.area } * 0.3f) return false
            val childScore = big.map { scoreOf[it] ?: 0f }.average().toFloat()
            val own = scoreOf[c] ?: 0f
            return childScore >= own - 0.08f || isPageAround(c, big)
        }

        // Best first; prints in an album don't overlap, so anything that overlaps a better one goes.
        // Equal scores go to the bigger one, so a print beats the shapes inside it.
        val result = mutableListOf<Quad>()
        for (q in quads.sortedBy { it.area }) if (isPage(q)) pages += q
        for (c in kept.filterNot { it.quad in pages }.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.quad.area })) {
            val over = result.firstOrNull { it.intersectionArea(c.quad) >= minOf(it.area, c.quad.area) * 0.15f }
            if (over == null) result += c.quad else why?.invoke(c.quad, "overlaps a better one at ${over.center}")
        }
        return result
    }
}
