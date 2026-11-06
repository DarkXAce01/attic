package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.RelationEntity
import io.github.darkxace01.attic.people.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FamilyTest {
    private fun parent(a: Long, b: Long) = RelationEntity(a = a, b = b, kind = RelationEntity.PARENT)
    private fun spouse(a: Long, b: Long) = RelationEntity(a = a, b = b, kind = RelationEntity.SPOUSE)

    // Nani (1) and Nana (2); their children Mum (3) and Uncle (4); Mum married Dad (5); their children 6 and 7.
    private val family = listOf(
        spouse(1, 2), parent(1, 3), parent(2, 3), parent(1, 4), parent(2, 4),
        spouse(3, 5), parent(3, 6), parent(5, 6), parent(3, 7), parent(5, 7),
    )

    @Test
    fun closeFamily() {
        val k = Family.kin(3, family)
        assertEquals(listOf(1L, 2L), k.parents)
        assertEquals(listOf(6L, 7L), k.children)
        assertEquals(listOf(5L), k.spouses)
        assertEquals(listOf(4L), k.siblings)
    }

    @Test
    fun generationsAndCouples() {
        val l = Family.layout(6, family)
        assertEquals(7, l.size)
        assertEquals(-2, l.getValue(1).generation)
        assertEquals(-1, l.getValue(3).generation)
        assertEquals(-1, l.getValue(5).generation)
        assertEquals(0, l.getValue(7).generation)
        // Couples side by side.
        assertEquals(1f, abs(l.getValue(1).x - l.getValue(2).x), 0.01f)
        assertEquals(1f, abs(l.getValue(3).x - l.getValue(5).x), 0.01f)
        // Mum sits between Nani and Nana's middle and her husband: on the side nearer her parents.
        val parentsMid = (l.getValue(1).x + l.getValue(2).x) / 2
        assertTrue(abs(l.getValue(3).x - parentsMid) <= abs(l.getValue(5).x - parentsMid))
    }
}

class FamilyMissingTest {
    @org.junit.Test
    fun noneAndOneOldestGenerationFirst() {
        val layout = mapOf(
            1L to io.github.darkxace01.attic.people.Family.Spot(0, 0f), 2L to io.github.darkxace01.attic.people.Family.Spot(-1, 0f),
            3L to io.github.darkxace01.attic.people.Family.Spot(-1, 1f), 4L to io.github.darkxace01.attic.people.Family.Spot(1, 0f),
        )
        val m = io.github.darkxace01.attic.people.Family.missing(layout, mapOf(1L to 12, 2L to 1, 4L to 0))
        org.junit.Assert.assertEquals(listOf(3L, 4L), m.none)
        org.junit.Assert.assertEquals(listOf(2L), m.one)
    }
}
