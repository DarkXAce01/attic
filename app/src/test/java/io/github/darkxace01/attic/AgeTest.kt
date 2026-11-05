package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.PhotoDate
import io.github.darkxace01.attic.people.Age
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AgeTest {
    @Test
    fun agesFromBirthAndPhotoDates() {
        val born = PhotoDate.day(LocalDate.of(1952, 7, 14))
        assertEquals(Age(7, false), Age.at(born, PhotoDate.day(LocalDate.of(1959, 8, 1))))
        assertEquals(Age(6, false), Age.at(born, PhotoDate.day(LocalDate.of(1959, 7, 13))))
        // A photo only known to a year is still exact enough; a decade is not.
        assertEquals(Age(34, false), Age.at(born, PhotoDate.year(1987)))
        assertEquals("About 22", Age.at(born, PhotoDate.decade(1970))!!.label)
        // Born only to a year: the age is about.
        assertEquals("About 10", Age.at(PhotoDate.about(1952), PhotoDate.year(1962))!!.label)
        assertEquals("Under 1", Age.at(born, PhotoDate.day(LocalDate.of(1953, 1, 1)))!!.label)
        assertNull(Age.at(born, PhotoDate.year(1950)))
    }
}
