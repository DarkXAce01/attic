package io.github.darkxace01.attic.data

import java.time.LocalDate
import java.time.YearMonth

/**
 * When a photo was taken, as precisely as anyone knows: a day, a month, a season, a year, "about"
 * a year, part of a decade, or a decade. Stored as a range plus a label, so "1980s" sorts as a band on
 * the timeline instead of pretending to be 1 January 1980.
 */
data class PhotoDate(val from: LocalDate, val to: LocalDate, val label: String) {
    /** The single date written into the file's EXIF, the middle of the range. */
    val representative: LocalDate get() = from.plusDays((to.toEpochDay() - from.toEpochDay()) / 2)

    companion object {
        private val months = listOf(
            "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
        )
        private val monthNames = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )

        fun day(d: LocalDate) = PhotoDate(d, d, "${d.dayOfMonth} ${monthNames[d.monthValue - 1]} ${d.year}")

        fun month(year: Int, month: Int): PhotoDate {
            val ym = YearMonth.of(year, month)
            return PhotoDate(ym.atDay(1), ym.atEndOfMonth(), "${monthNames[month - 1]} $year")
        }

        fun year(y: Int) = PhotoDate(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "$y")

        fun about(y: Int) = PhotoDate(LocalDate.of(y - 1, 1, 1), LocalDate.of(y + 1, 12, 31), "About $y")

        fun decade(start: Int) = PhotoDate(LocalDate.of(start, 1, 1), LocalDate.of(start + 9, 12, 31), "${start}s")

        /**
         * Reads what people actually type: "1987", "'87", "about 1987", "summer 1987", "jul 1998",
         * "14 jul 1998", "14/7/1998", "1998-07-14", "'98 7 14" (a camera's date stamp), "1980s", "80s",
         * "early 90s". Day-first for numeric dates. Returns null when it can't tell.
         */
        fun parse(input: String, today: LocalDate = LocalDate.now()): PhotoDate? {
            val raw = input.trim().lowercase()
            if (raw.isEmpty()) return null
            val text = raw.replace(Regex("[,]"), " ").replace(Regex("\\s+"), " ")

            fun fullYear(token: String): Int? {
                val digits = token.trimStart('\'', '’')
                val n = digits.toIntOrNull() ?: return null
                return when (digits.length) {
                    4 -> n.takeIf { it in 1826..today.year }
                    2 -> if (n <= today.year % 100) 2000 + n else 1900 + n
                    else -> null
                }
            }

            // Decades: "1980s", "80s", "the 80s", "early 90s", "mid 1970s", "late 80s".
            Regex("^(?:the )?(early|mid|late)? ?(?:the )?('?\\d{2}|\\d{4})'?s$").find(text)?.let { m ->
                val y = fullYear(m.groupValues[2].trimStart('\'')) ?: return null
                val start = y - y % 10
                return when (m.groupValues[1]) {
                    "early" -> PhotoDate(LocalDate.of(start, 1, 1), LocalDate.of(start + 3, 12, 31), "Early ${start}s")
                    "mid" -> PhotoDate(LocalDate.of(start + 3, 1, 1), LocalDate.of(start + 6, 12, 31), "Mid ${start}s")
                    "late" -> PhotoDate(LocalDate.of(start + 6, 1, 1), LocalDate.of(start + 9, 12, 31), "Late ${start}s")
                    else -> decade(start)
                }
            }

            // "about 1987", "around '87", "circa 1987", "c. 1987", "~1987".
            Regex("^(?:about|around|approx\\.?|circa|ca\\.?|c\\.|~) ?('?\\d{2,4})$").find(text)?.let { m ->
                return fullYear(m.groupValues[1])?.let(::about)
            }

            // Seasons. Winter spans the turn of the year: winter 1987 = Dec 1987 to Feb 1988.
            Regex("^(spring|summer|autumn|fall|winter) ('?\\d{2,4})$").find(text)?.let { m ->
                val y = fullYear(m.groupValues[2]) ?: return null
                val season = m.groupValues[1]
                val (from, to) = when (season) {
                    "spring" -> LocalDate.of(y, 3, 1) to LocalDate.of(y, 5, 31)
                    "summer" -> LocalDate.of(y, 6, 1) to LocalDate.of(y, 8, 31)
                    "autumn", "fall" -> LocalDate.of(y, 9, 1) to LocalDate.of(y, 11, 30)
                    else -> LocalDate.of(y, 12, 1) to YearMonth.of(y + 1, 2).atEndOfMonth()
                }
                val name = if (season == "fall") "Autumn" else season.replaceFirstChar { it.uppercase() }
                return PhotoDate(from, to, "$name $y")
            }

            // A plain year.
            if (Regex("^'?\\d{2}$|^\\d{4}$").matches(text)) return fullYear(text)?.let(::year)

            val tokens = text.split(Regex("[ /.\\-]+")).filter { it.isNotEmpty() }
            fun monthOf(t: String): Int? = months.indexOfFirst { t.startsWith(it) }.takeIf { it >= 0 && t.length >= 3 }?.plus(1)

            // Month with a name: "jul 1998", "july '98", "14 jul 1998", "jul 14 1998".
            val named = tokens.indexOfFirst { monthOf(it) != null }
            if (named >= 0) {
                val month = monthOf(tokens[named])!!
                val rest = tokens.filterIndexed { i, _ -> i != named }
                val yearTok = rest.lastOrNull { it.trimStart('\'').length == 4 || it.startsWith("'") } ?: rest.lastOrNull()
                val y = yearTok?.let(::fullYear) ?: return null
                val dayTok = rest.firstOrNull { it != yearTok }
                return if (dayTok == null) {
                    month(y, month)
                } else {
                    val d = dayTok.trimEnd('s', 't', 'n', 'd', 'r', 'h').toIntOrNull() ?: return null
                    runCatching { day(LocalDate.of(y, month, d)) }.getOrNull()
                }
            }

            // All numbers. "1998-07-14" year first; "'98 7 14" a camera date stamp, year first;
            // otherwise day first: "14/7/1998", "14.07.98". Two numbers: month and year.
            if (tokens.all { it.trimStart('\'').all(Char::isDigit) }) {
                return when (tokens.size) {
                    3 -> {
                        val yearFirst = tokens[0].length == 4 || tokens[0].startsWith("'")
                        val (y, m, d) = if (yearFirst) {
                            Triple(fullYear(tokens[0]), tokens[1].toInt(), tokens[2].toInt())
                        } else {
                            Triple(fullYear(tokens[2]), tokens[1].toInt(), tokens[0].toInt())
                        }
                        y?.let { runCatching { day(LocalDate.of(it, m, d)) }.getOrNull() }
                    }
                    2 -> {
                        val (a, b) = tokens
                        when {
                            a.length == 4 -> fullYear(a)?.let { y -> b.toInt().takeIf { it in 1..12 }?.let { month(y, it) } }
                            else -> fullYear(b)?.let { y -> a.toInt().takeIf { it in 1..12 }?.let { month(y, it) } }
                        }
                    }
                    else -> null
                }
            }
            return null
        }
    }
}
