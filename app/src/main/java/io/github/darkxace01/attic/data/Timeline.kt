package io.github.darkxace01.attic.data

/**
 * Every photo across all albums, oldest first, the way a family's story reads. Photos without their own
 * date use their album's. A photo dated to a year (or a season, month or day in it) sits in that year;
 * one dated only roughly ("about 1985", "the 80s") sits after the years of its decade, so a guess never
 * pretends to be exact; photos with no date at all come last.
 */
object Timeline {
    sealed interface Row {
        /** "1980s", over the decade's years. */
        data class Decade(val decade: Int, val count: Int) : Row

        /** A year, a decade's loose photos, or the undated ones. */
        data class Group(val key: String, val title: String, val prints: List<PrintEntity>) : Row
    }

    /** Longest a date can be and still belong to one year: a year, plus slack for a season across new year. */
    private const val YEAR_DAYS = 400

    fun rows(prints: List<PrintEntity>, albumDates: Map<Long, PhotoDate?>): List<Row> {
        val dated = prints.map { p -> p to (p.date ?: albumDates[p.albumId]) }
        val undated = dated.filter { it.second == null }.map { it.first }
        val withDate = dated.mapNotNull { (p, d) -> d?.let { p to it } }
        val byDecade = withDate.groupBy { (_, d) -> d.representative.year / 10 * 10 }

        val out = mutableListOf<Row>()
        for (decade in byDecade.keys.sorted()) {
            val inDecade = byDecade.getValue(decade)
            out += Row.Decade(decade, inDecade.size)
            val (exact, loose) = inDecade.partition { (_, d) -> d.to.toEpochDay() - d.from.toEpochDay() <= YEAR_DAYS }
            for ((year, list) in exact.groupBy { (_, d) -> d.representative.year }.toSortedMap()) {
                out += Row.Group("y$year", "$year", list.sortedWith(order).map { it.first })
            }
            if (loose.isNotEmpty()) {
                out += Row.Group("d$decade", "Sometime in the ${decade}s", loose.sortedWith(order).map { it.first })
            }
        }
        if (undated.isNotEmpty()) {
            out += Row.Group("none", "No date yet", undated.sortedWith(compareBy({ it.albumId }, { it.page }, { it.position })))
        }
        return out
    }

    private val order = compareBy<Pair<PrintEntity, PhotoDate>>({ it.second.from }, { it.second.to }, { it.first.createdAt }, { it.first.position })
}
