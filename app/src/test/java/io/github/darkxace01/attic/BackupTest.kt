package io.github.darkxace01.attic

import io.github.darkxace01.attic.data.Backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    @Test
    fun enhancedLooksAreMadeAgainSoNotBackedUp() {
        assertTrue(Backup.isDerived("print_1_e1.png"))
        assertTrue(Backup.isDerived("print_12_e2.png"))
        assertTrue(Backup.isDerived("print_3_fix.png"))
        // Everything Attic can't make again is kept.
        for (name in listOf("print_1.png", "original.jpg", "shot_2.jpg", "frame.json", "frame_2.json", "sweep_print_1.json", "meta.json")) {
            assertFalse(name, Backup.isDerived(name))
        }
    }

    @Test
    fun albumNamesWorkOnAnyCard() {
        assertEquals("Nani's red album", Backup.safeName("Nani's red album"))
        assertEquals("Delhi 1987 summer", Backup.safeName("Delhi/1987: summer?"))
        assertEquals("Trip", Backup.safeName("Trip..."))
        assertEquals("Attic", Backup.safeName("  /// "))
    }
}
