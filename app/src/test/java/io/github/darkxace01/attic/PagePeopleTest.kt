package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.Library.Companion.pagePeople
import org.junit.Assert.assertEquals
import org.junit.Test

class PagePeopleTest {
    @Test
    fun aNameAddedToThePageGoesOnEveryPhoto() {
        assertEquals(listOf("Papa", "Nani"), pagePeople(listOf("Papa"), emptyList(), listOf("Nani")))
        assertEquals(listOf("Nani"), pagePeople(emptyList(), emptyList(), listOf("Nani")))
    }

    @Test
    fun aNameRemovedFromThePageComesOffEveryPhotoButOthersStay() {
        assertEquals(listOf("Papa"), pagePeople(listOf("Nani", "Papa"), listOf("Nani"), emptyList()))
    }

    @Test
    fun namesAreMatchedWhateverTheCase() {
        assertEquals(listOf("nani"), pagePeople(listOf("nani"), emptyList(), listOf("Nani")))
        assertEquals(emptyList<String>(), pagePeople(listOf("nani"), listOf("Nani"), emptyList()))
    }
}
