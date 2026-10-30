package io.github.darkxace01.album.vision

/**
 * Turns raw rectangle candidates into the set of prints.
 *
 * Contours on an edge map come in pairs (the outer and inner boundary of the same edge line), album
 * pages enclose the prints on them, and a print's image area sits inside its white border. So:
 *  1. near-identical candidates are merged (their corners averaged, which lands on the real edge),
 *  2. anything enclosing two or more others is a page, not a print, and is dropped,
 *  3. anything inside another candidate is a detail of that print and is dropped.
 */
object Candidates {
    const val SAME_IOU = 0.82f

    fun resolve(raw: List<Quad>): List<Quad> {
        val merged = mutableListOf<Quad>()
        for (q in raw.sortedByDescending { it.area }) {
            val i = merged.indexOfFirst { it.iou(q) >= SAME_IOU }
            if (i >= 0) merged[i] = Quad.average(merged[i], q) else merged += q
        }

        fun encloses(outer: Quad, inner: Quad) =
            outer !== inner && inner.area < outer.area * 0.9f && inner.points.all { outer.contains(it) }

        // A page holds two or more prints side by side. A single print can hold nested shapes (its image
        // area, a window in the photo), so only the outermost things inside count.
        fun isPage(c: Quad): Boolean {
            val inside = merged.filter { encloses(c, it) }
            return inside.count { child -> inside.none { encloses(it, child) } } >= 2
        }

        val notPages = merged.filterNot(::isPage)
        return notPages.filter { inner -> notPages.none { encloses(it, inner) } }
    }
}
