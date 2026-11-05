package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.storyLength
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryTest {
    @Test
    fun lengthsReadLikeAClock() {
        assertEquals("0:07", storyLength(7_000))
        assertEquals("0:07", storyLength(6_600))
        assertEquals("1:24", storyLength(84_000))
        assertEquals("12:03", storyLength(723_000))
        assertEquals("0:00", storyLength(0))
    }
}
