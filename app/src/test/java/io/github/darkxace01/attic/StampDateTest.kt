package io.github.darkxace01.attic

import io.github.darkxace01.attic.vision.StampDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StampDateTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun yearFirst() {
        assertEquals(listOf(d(1987, 7, 14)), StampDate.read("'87 7 14"))
        assertEquals(listOf(d(1994, 2, 20)), StampDate.read("'94 02 20"))
        assertEquals(listOf(d(2001, 3, 9)), StampDate.read("'01 3 9"))
    }

    @Test
    fun yearLastIsReadOneWayWhenANumberIsOverTwelve() {
        assertEquals(listOf(d(1987, 7, 14)), StampDate.read("7 14 '87"))
        assertEquals(listOf(d(1987, 7, 14)), StampDate.read("14 7 '87"))
    }

    @Test
    fun yearLastCanReadBothWays() {
        // 3 May or 5 March: month first is the likelier.
        assertEquals(listOf(d(1988, 3, 5), d(1988, 5, 3)), StampDate.read("3 5 '88"))
        assertEquals(listOf(d(1988, 4, 4)), StampDate.read("4 4 '88"))
    }

    @Test
    fun withoutTheApostrophe() {
        assertEquals(listOf(d(1991, 3, 6), d(1991, 6, 3)), StampDate.read("3 6 91"))
        assertEquals(listOf(d(1985, 12, 1)), StampDate.read("85 12 1"))
    }

    @Test
    fun notADate() {
        assertEquals(emptyList<LocalDate>(), StampDate.read(""))
        assertEquals(emptyList<LocalDate>(), StampDate.read("12"))
        assertEquals(emptyList<LocalDate>(), StampDate.read("'87 13 40"))
        assertEquals(emptyList<LocalDate>(), StampDate.read("'87 2 30"))
        assertEquals(emptyList<LocalDate>(), StampDate.read("7 '87 14"))
        assertEquals(emptyList<LocalDate>(), StampDate.read("'87 7 14 3"))
    }
}
