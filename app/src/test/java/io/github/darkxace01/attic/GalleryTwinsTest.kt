package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.GalleryTwins
import io.github.darkxace01.attic.data.PrintEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTwinsTest {
    private fun print(id: Long) = PrintEntity(
        id = id, albumId = 1, scanDir = "s", fileName = "p.png", page = 1, position = 0,
        width = 1, height = 1, sharpness = 100.0, quad = "0,0,1,0,1,1,0,1", createdAt = 0,
    )

    @Test
    fun aCopyAnyWayUpMatchesAStrangerDoesNot() {
        val a = 0x0F0F_0F0F_0F0F_0F0FL
        val b = 0x3333_5555_3333_5555L
        val turned = 0x7A7A_1234_5678_9ABCL
        val gallery = mapOf(
            10L to (a xor 0b1011L), // the same picture, recompressed: 3 bits off
            11L to (b xor 0x0000_0000_0000_3FFFL), // 14 bits off: close enough for two scans, not for a gallery
            12L to turned, // the print the other way up
        )
        val found = GalleryTwins.match(listOf(print(1) to listOf(a), print(2) to listOf(b), print(3) to listOf(1L, turned)), gallery)
        assertEquals(listOf(1L to 10L, 3L to 12L), found.map { it.first.id to it.second })
    }
}
