package io.github.darkxace01.attic.vision

import java.time.LocalDate

/**
 * What a camera's date stamp says. Cameras printed the year first ('87 7 14) or last (7 14 '87 or
 * 14 7 '87), the year marked with an apostrophe on most. With the year last, month-day and day-month
 * look the same unless one of the numbers is over 12, so a stamp can have two readings.
 */
object StampDate {
    /** The dates [text] can mean, most likely first: empty if it isn't a date, two if it's ambiguous. */
    fun read(text: String): List<LocalDate> {
        val groups = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (groups.size != 3) return emptyList()
        val ticked = groups.indexOfFirst { it.startsWith("'") }
        if (groups.withIndex().any { (i, g) -> !g.trimStart('\'').all(Char::isDigit) || g.trimStart('\'').isEmpty() || ('\'' in g && i != ticked) }) return emptyList()
        val n = groups.map { it.trimStart('\'').toInt() }
        val lens = groups.map { it.trimStart('\'').length }
        val yearAt = when {
            ticked == 0 || ticked == 2 -> ticked
            ticked == 1 -> return emptyList()
            // No apostrophe: the year is the 2-digit group at either end. Year-first cameras padded or
            // used 2 digits for the year anyway; when both ends could be the year, it can't be told.
            lens[0] == 2 && lens[2] != 2 -> 0
            lens[2] == 2 && lens[0] != 2 -> 2
            lens[0] == 2 && lens[2] == 2 -> return (dates(n, 0) + dates(n, 2)).distinct()
            else -> return emptyList()
        }
        if (lens[yearAt] != 2) return emptyList()
        return dates(n, yearAt)
    }

    private fun dates(n: List<Int>, yearAt: Int): List<LocalDate> {
        // Stamps were used from the late 70s to the early 2000s.
        val year = n[yearAt].let { if (it >= 70) 1900 + it else 2000 + it }
        return if (yearAt == 0) {
            listOfNotNull(date(year, n[1], n[2]))
        } else {
            // Month first was the more common order on cameras sold around the world.
            listOfNotNull(date(year, n[0], n[1]), date(year, n[1], n[0])).distinct()
        }
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? =
        if (m in 1..12 && d in 1..31) runCatching { LocalDate.of(y, m, d) }.getOrNull() else null
}
