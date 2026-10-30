package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.PhotoDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PhotoDateTest {
    private val today = LocalDate.of(2026, 10, 30)
    private fun p(s: String) = PhotoDate.parse(s, today)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun years() {
        assertEquals(PhotoDate(d(1987, 1, 1), d(1987, 12, 31), "1987"), p("1987"))
        assertEquals("1987", p("'87")?.label)
        assertEquals("2004", p("04")?.label)
    }

    @Test
    fun about() {
        assertEquals(PhotoDate(d(1986, 1, 1), d(1988, 12, 31), "About 1987"), p("about 1987"))
        assertEquals("About 1987", p("around '87")?.label)
        assertEquals("About 1987", p("c. 1987")?.label)
        assertEquals("About 1987", p("~1987")?.label)
    }

    @Test
    fun decades() {
        assertEquals(PhotoDate(d(1980, 1, 1), d(1989, 12, 31), "1980s"), p("1980s"))
        assertEquals("1980s", p("80s")?.label)
        assertEquals("1980s", p("the 80s")?.label)
        assertEquals(PhotoDate(d(1990, 1, 1), d(1993, 12, 31), "Early 1990s"), p("early 90s"))
        assertEquals("Late 1970s", p("Late 1970s")?.label)
    }

    @Test
    fun seasons() {
        assertEquals(PhotoDate(d(1987, 6, 1), d(1987, 8, 31), "Summer 1987"), p("summer 1987"))
        assertEquals(PhotoDate(d(1987, 12, 1), d(1988, 2, 29), "Winter 1987"), p("winter '87"))
        assertEquals("Autumn 1990", p("fall 1990")?.label)
    }

    @Test
    fun months() {
        assertEquals(PhotoDate(d(1998, 7, 1), d(1998, 7, 31), "Jul 1998"), p("jul 1998"))
        assertEquals("Jul 1998", p("July '98")?.label)
        assertEquals("Jul 1998", p("7/1998")?.label)
        assertEquals("Jul 1998", p("1998-07")?.label)
    }

    @Test
    fun days() {
        val expected = PhotoDate(d(1998, 7, 14), d(1998, 7, 14), "14 Jul 1998")
        assertEquals(expected, p("14 jul 1998"))
        assertEquals(expected, p("July 14th, 1998"))
        assertEquals(expected, p("14/7/1998"))
        assertEquals(expected, p("14.07.98"))
        assertEquals(expected, p("1998-07-14"))
        // The orange date stamp printed by 90s cameras: year first.
        assertEquals(expected, p("'98 7 14"))
    }

    @Test
    fun nonsenseIsRejected() {
        assertNull(p(""))
        assertNull(p("grandma"))
        assertNull(p("31/2/1990"))
        assertNull(p("3000"))
    }

    @Test
    fun representativeDateIsTheMiddle() {
        assertEquals(d(1984, 12, 31), p("1980s")!!.representative)
    }
}
