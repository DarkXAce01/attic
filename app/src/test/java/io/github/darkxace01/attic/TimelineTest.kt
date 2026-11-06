package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.data.Timeline
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTest {
    private var nextId = 1L
    private fun print(date: PhotoDate?, album: Long = 1) = PrintEntity(
        id = nextId++, albumId = album, scanDir = "s", fileName = "p.png", page = 1, position = nextId.toInt(),
        width = 1, height = 1, sharpness = 100.0, quad = "0,0,1,0,1,1,0,1",
        dateFrom = date?.from?.toEpochDay(), dateTo = date?.to?.toEpochDay(), dateLabel = date?.label, createdAt = 0,
    )

    private fun titles(rows: List<Timeline.Row>) = rows.map {
        when (it) {
            is Timeline.Row.Decade -> "${it.decade}s (${it.count})"
            is Timeline.Row.Group -> "${it.title}: ${it.prints.size}"
        }
    }

    @Test
    fun yearsInDecadesOldestFirstWithRoughDatesAfterTheirDecadeAndUndatedLast() {
        val prints = listOf(
            print(PhotoDate.year(1992)),
            print(PhotoDate.parse("summer 87")),
            print(PhotoDate.about(1985)),
            print(PhotoDate.decade(1980)),
            print(PhotoDate.parse("14/7/1987")),
            print(null),
            print(null, album = 2),
        )
        val rows = Timeline.rows(prints, mapOf(2L to PhotoDate.year(1979)))
        assertEquals(
            listOf("1970s (1)", "1979: 1", "1980s (4)", "1987: 2", "Sometime in the 1980s: 2", "1990s (1)", "1992: 1", "No date yet: 1"),
            titles(rows),
        )
    }

    @Test
    fun withinAYearTheEarlierDateComesFirst() {
        val july = print(PhotoDate.parse("14/7/1987"))
        val summer = print(PhotoDate.parse("summer 87"))
        val jan = print(PhotoDate.parse("jan 1987"))
        val year = (Timeline.rows(listOf(july, summer, jan), emptyMap())[1] as Timeline.Row.Group).prints
        assertEquals(listOf(jan.id, summer.id, july.id), year.map { it.id })
    }

    @Test
    fun phonePhotosJoinTheirYear() {
        val old = print(PhotoDate.year(2012))
        val phone = listOf(
            io.github.darkxace01.attic.data.PhonePhoto(2, "content://m/2", java.time.LocalDate.of(2012, 8, 1), 1, 1),
            io.github.darkxace01.attic.data.PhonePhoto(1, "content://m/1", java.time.LocalDate.of(2012, 3, 1), 1, 1),
            io.github.darkxace01.attic.data.PhonePhoto(3, "content://m/3", java.time.LocalDate.of(2024, 1, 5), 1, 1),
        )
        val rows = Timeline.rows(listOf(old), emptyMap(), phone)
        assertEquals(listOf("2010s (3)", "2012: 3", "2020s (1)", "2024: 1"), rows.map {
            when (it) { is Timeline.Row.Decade -> "${it.decade}s (${it.count})"; is Timeline.Row.Group -> "${it.title}: ${it.count}" }
        })
        val y2012 = rows[1] as Timeline.Row.Group
        assertEquals(listOf(old.id), y2012.prints.map { it.id })
        assertEquals(listOf(1L, 2L), y2012.phone.map { it.id })
    }
}
