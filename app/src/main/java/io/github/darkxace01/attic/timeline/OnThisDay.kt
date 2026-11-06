package io.github.darkxace01.attic.timeline

import io.github.darkxace01.attic.data.PhonePhoto
import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.data.PrintEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * What to bring back today, from the photos' dates. Old prints are mostly dated to a year at best, so it
 * falls back from the exact day to the week, then the month, and failing all that picks a photo for the
 * day, the same one all day.
 */
object OnThisDay {
    /** Scans first, then the phone's own photos from the same days. */
    data class Memory(val title: String, val prints: List<Pair<PrintEntity, PhotoDate?>>, val phone: List<PhonePhoto> = emptyList())

    /** Days either side of today that still count as "this week". */
    private const val WEEK = 3
    private const val MAX = 12

    fun pick(prints: List<PrintEntity>, albumDates: Map<Long, PhotoDate?>, today: LocalDate, phone: List<PhonePhoto> = emptyList()): Memory? {
        if (prints.isEmpty()) return null
        val oldPhone = phone.filter { it.taken.year < today.year }
        val dated = prints.map { it to (it.date ?: albumDates[it.albumId]) }
        val past = dated.mapNotNull { (p, d) -> d?.takeIf { it.from.year < today.year }?.let { p to it } }
        val days = past.filter { it.second.from == it.second.to }

        fun sameDay(d: LocalDate) = d.withYear(today.year).let { it == today } ||
            // Born on 29 February: remembered on the 28th in other years.
            (d.monthValue == 2 && d.dayOfMonth == 29 && !today.isLeapYear && today.monthValue == 2 && today.dayOfMonth == 28)
        fun daysOff(d: LocalDate): Long {
            val here = runCatching { d.withYear(today.year) }.getOrElse { d.withDayOfMonth(28).withYear(today.year) }
            return minOf(abs(ChronoUnit.DAYS.between(here, today)), abs(ChronoUnit.DAYS.between(here.minusYears(1), today)), abs(ChronoUnit.DAYS.between(here.plusYears(1), today)))
        }
        val oldestFirst = compareBy<Pair<PrintEntity, PhotoDate>>({ it.second.from }, { it.first.id })

        fun memory(title: String, scans: List<Pair<PrintEntity, PhotoDate>>, ph: List<PhonePhoto>): Memory? {
            if (scans.isEmpty() && ph.isEmpty()) return null
            val s = scans.sortedWith(oldestFirst).take(MAX)
            return Memory(title, s, ph.sortedWith(compareBy({ it.taken }, { it.id })).take(MAX - s.size))
        }
        memory("On this day", days.filter { sameDay(it.second.from) }, oldPhone.filter { sameDay(it.taken) })?.let { return it }
        memory("This week, years ago", days.filter { daysOff(it.second.from) <= WEEK }, oldPhone.filter { daysOff(it.taken) <= WEEK })?.let { return it }
        past.filter { (_, d) -> d.from.year == d.to.year && d.from.monthValue == d.to.monthValue && d.from.monthValue == today.monthValue }
            .takeIf { it.isNotEmpty() }?.let { return Memory("This month, years ago", it.sortedWith(oldestFirst).take(MAX)) }
        // Nothing dated near today: one photo for the day, steady all day, a different one tomorrow.
        val pool = prints.sortedBy { it.id }
        val seed = today.toEpochDay() * 2654435761L
        val p = pool[Math.floorMod(seed, pool.size.toLong()).toInt()]
        return Memory("A photo for today", listOf(p to (p.date ?: albumDates[p.albumId])))
    }

    /** "28 years ago", or "Years ago" when the date is too loose to say. */
    fun ago(d: PhotoDate?, today: LocalDate): String? {
        d ?: return null
        val years = today.year - d.representative.year
        return when {
            years <= 0 -> null
            d.to.year - d.from.year > 1 -> "About $years years ago"
            years == 1 -> "A year ago"
            else -> "$years years ago"
        }
    }
}
