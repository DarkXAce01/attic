package io.github.darkxace01.attic.ui

import io.github.darkxace01.attic.data.AlbumSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumLineTest {
    private fun a(count: Int, pages: Int, phone: Int, date: String? = null) = AlbumSummary(1, "A", date, count, pages, null, null, null, phone = phone)

    @Test fun scansOnly() = assertEquals("2 pages · 5 photos", albumLine(a(5, 2, 0)))
    @Test fun phoneOnly() = assertEquals("3 photos from your phone", albumLine(a(0, 0, 3)))
    @Test fun both() = assertEquals("1975 · 1 page · 4 photos · 2 from your phone", albumLine(a(4, 1, 2, "1975")))
    @Test fun empty() = assertEquals("0 photos", albumLine(a(0, 0, 0)))
}
