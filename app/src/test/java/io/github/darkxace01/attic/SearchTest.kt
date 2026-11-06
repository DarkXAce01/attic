package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.data.Search
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private var nextId = 1L
    private fun known(date: PhotoDate?, people: List<String> = emptyList(), place: String? = null, caption: String? = null, album: String = "Blue album", story: Boolean = false) =
        Search.Known(
            PrintEntity(
                id = nextId++, albumId = 1, scanDir = "s", fileName = "p.png", page = 1, position = nextId.toInt(),
                width = 1, height = 1, sharpness = 100.0, quad = "0,0,1,0,1,1,0,1", place = place, caption = caption,
                dateFrom = date?.from?.toEpochDay(), dateTo = date?.to?.toEpochDay(), dateLabel = date?.label, createdAt = 0,
            ),
            date, album, people, story,
        )

    private val library = listOf(
        known(PhotoDate.parse("summer 87"), listOf("Anna", "Ravi"), "Goa", "Beach day"), // 1
        known(PhotoDate.year(1992), listOf("Anna"), "Pune"), // 2
        known(PhotoDate.decade(1980), listOf("Joanne"), "Goa", story = true), // 3
        known(null, listOf("Ravi"), album = "Wedding"), // 4
        known(PhotoDate.day(java.time.LocalDate.of(1987, 7, 14)), listOf("Anna"), "Goa"), // 5
    )

    private fun ids(q: String) = Search.find(q, library).map { it.id }

    @Test
    fun namesMatchFromTheStartOfAWordOldestFirst() {
        assertEquals(listOf(1L, 5L, 2L), ids("ann"))
        assertEquals(listOf(3L), ids("joanne"))
        assertEquals(listOf(1L, 4L), ids("Ravi"))
    }

    @Test
    fun everyWordHasToMatch() {
        assertEquals(listOf(1L, 5L), ids("anna goa"))
        assertEquals(listOf(1L), ids("beach ravi"))
        assertEquals(listOf(4L), ids("wedding"))
    }

    @Test
    fun datesFindOverlappingPhotosButNotLooserOnes() {
        // 1987 finds summer 87 and 14 Jul 1987; "the 80s" might be 1987 but showing it would be a guess.
        assertEquals(listOf(1L, 5L), ids("1987"))
        assertEquals(listOf(3L, 1L, 5L), ids("80s"))
        assertEquals(listOf(5L), ids("14 jul 1987"))
        assertEquals(listOf(1L, 5L), ids("goa 1987"))
    }

    @Test
    fun storiesAndUndated() {
        assertEquals(listOf(3L), ids("story"))
        assertEquals(listOf(4L), ids("undated"))
        assertEquals(emptyList<Long>(), ids("   "))
    }

    @Test
    fun smartAlbumsForPeopleDecadesAndPlacesWithEnoughPhotos() {
        val smart = Search.smart(library).map { "${it.title}: ${it.prints.map { p -> p.id }}" }
        assertEquals(listOf("Anna: [1, 5, 2]", "The 1980s: [3, 1, 5]", "Goa: [3, 1, 5]", "With a story: [3]"), smart)
    }
}
