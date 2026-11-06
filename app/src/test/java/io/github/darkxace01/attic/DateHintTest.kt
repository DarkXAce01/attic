package io.github.darkxace01.attic

import io.github.darkxace01.attic.people.DateHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DateHintTest {
    private val rnd = Random(7)
    private fun randomVec() = FloatArray(128) { rnd.nextFloat() * 2 - 1 }
    private val ravi = randomVec()
    private val anna = randomVec()
    private val ageing = randomVec()

    /** A face of [who] in [year]: the same person, drifting with age, plus a little noise. */
    private fun face(who: FloatArray, year: Int, aging: Float = 0.06f, noise: Float = 0.15f) =
        FloatArray(128) { who[it] + (year - 1960) * aging * ageing[it] + (rnd.nextFloat() * 2 - 1) * noise }

    private fun seen(who: FloatArray, years: IntProgression, aging: Float = 0.06f) = years.map { DateHint.Seen(face(who, it, aging), it) }

    @Test
    fun aFaceThatClearlyLooksLikeOneTimeSuggestsIt() {
        val h = DateHint.suggest(listOf("Ravi" to face(ravi, 1984)), mapOf("Ravi" to seen(ravi, 1962..1998 step 3)))
        assertNotNull(h)
        assertTrue("${h!!.year}", h.year in 1980..1988)
        assertEquals(listOf("Ravi"), h.names)
    }

    @Test
    fun notEnoughDatedPhotosOrYearsSaysNothing() {
        assertNull(DateHint.suggest(listOf("Ravi" to face(ravi, 1984)), mapOf("Ravi" to seen(ravi, 1980..1989 step 4))))
        assertNull(DateHint.suggest(listOf("Ravi" to face(ravi, 1984)), mapOf("Ravi" to seen(ravi, 1982..1986 step 1))))
        assertNull(DateHint.suggest(listOf("Ravi" to face(ravi, 1984)), emptyMap()))
    }

    @Test
    fun aFaceThatDoesntChangeWithTheYearsSaysNothing() {
        // Grown up: the face hardly changes, so no year is clearly closer than the rest.
        val h = DateHint.suggest(listOf("Ravi" to face(ravi, 1984, aging = 0f)), mapOf("Ravi" to seen(ravi, 1962..1998 step 3, aging = 0f)))
        assertNull(h)
    }

    @Test
    fun twoPeopleWhoDisagreeSayNothing() {
        // Anna's dated photos are labelled ten years out, so her face points elsewhere.
        val annaSeen = (1962..1998 step 3).map { DateHint.Seen(face(anna, it), it + 12) }
        val h = DateHint.suggest(
            listOf("Ravi" to face(ravi, 1984), "Anna" to face(anna, 1984)),
            mapOf("Ravi" to seen(ravi, 1962..1998 step 3), "Anna" to annaSeen),
        )
        assertNull(h)
    }
}
