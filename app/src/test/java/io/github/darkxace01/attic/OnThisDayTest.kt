package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.timeline.OnThisDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class OnThisDayTest {
    private var nextId = 1L
    private fun print(date: PhotoDate?, album: Long = 1) = PrintEntity(
        id = nextId++, albumId = album, scanDir = "s", fileName = "p.png", page = 1, position = nextId.toInt(),
        width = 1, height = 1, sharpness = 100.0, quad = "0,0,1,0,1,1,0,1",
        dateFrom = date?.from?.toEpochDay(), dateTo = date?.to?.toEpochDay(), dateLabel = date?.label, createdAt = 0,
    )
    private val today = LocalDate.of(2026, 11, 6)

    @Test
    fun fallsBackFromTheDayToTheMonth() {
        val sameDay = print(PhotoDate.day(LocalDate.of(1994, 11, 6)))
        val nearDay = print(PhotoDate.day(LocalDate.of(1990, 11, 8)))
        val month = print(PhotoDate.month(1988, 11))
        val year = print(PhotoDate.year(1985))
        val thisYear = print(PhotoDate.day(LocalDate.of(2026, 11, 6)))

        val all = listOf(sameDay, nearDay, month, year, thisYear)
        val a = OnThisDay.pick(all, emptyMap(), today)!!
        assertEquals("On this day", a.title)
        assertEquals(listOf(sameDay.id), a.prints.map { it.first.id })

        val b = OnThisDay.pick(all - sameDay, emptyMap(), today)!!
        assertEquals("This week, years ago", b.title)
        assertEquals(listOf(nearDay.id), b.prints.map { it.first.id })

        val c = OnThisDay.pick(listOf(month, year, thisYear), emptyMap(), today)!!
        assertEquals("This month, years ago", c.title)
        assertEquals(listOf(month.id), c.prints.map { it.first.id })
    }

    @Test
    fun weekWrapsAcrossNewYear() {
        val p = print(PhotoDate.day(LocalDate.of(1999, 12, 30)))
        assertEquals("This week, years ago", OnThisDay.pick(listOf(p), emptyMap(), LocalDate.of(2027, 1, 2))!!.title)
    }

    @Test
    fun albumDateCountsAndSomethingAlwaysComesBack() {
        val p = print(null, album = 7)
        val m = OnThisDay.pick(listOf(p), mapOf(7L to PhotoDate.month(1979, 11)), today)!!
        assertEquals("This month, years ago", m.title)
        val pool = listOf(print(PhotoDate.year(1970)), print(null), print(null))
        val r = OnThisDay.pick(pool, emptyMap(), today)!!
        assertEquals("A photo for today", r.title)
        // Steady: the same photo however the list comes, all day.
        assertEquals(r.prints[0].first.id, OnThisDay.pick(pool.reversed(), emptyMap(), today)!!.prints[0].first.id)
        assertNull(OnThisDay.pick(emptyList(), emptyMap(), today))
    }

    @Test
    fun yearsAgo() {
        assertEquals("32 years ago", OnThisDay.ago(PhotoDate.day(LocalDate.of(1994, 1, 1)), today))
        assertEquals("About 41 years ago", OnThisDay.ago(PhotoDate.about(1985), today))
        assertNull(OnThisDay.ago(null, today))
    }
}

class MemoryWidgetLineTest {
    @org.junit.Test
    fun dateAndHowLongAgo() {
        val today = java.time.LocalDate.of(2026, 7, 14)
        org.junit.Assert.assertEquals("14 Jul 1987, 39 years ago", io.github.darkxace01.attic.timeline.MemoryWidget.line(io.github.darkxace01.attic.data.PhotoDate.day(java.time.LocalDate.of(1987, 7, 14)), today))
        org.junit.Assert.assertEquals("1980s, about 42 years ago", io.github.darkxace01.attic.timeline.MemoryWidget.line(io.github.darkxace01.attic.data.PhotoDate.decade(1980), today))
        org.junit.Assert.assertEquals("No date yet", io.github.darkxace01.attic.timeline.MemoryWidget.line(null, today))
    }
}
