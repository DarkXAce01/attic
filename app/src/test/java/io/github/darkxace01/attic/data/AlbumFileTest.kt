package io.github.darkxace01.attic.data

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.github.darkxace01.attic.albumFile
import io.github.darkxace01.attic.library
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An album shared as a file comes back whole, and a second copy only fills in what's missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlbumFileTest {
    companion object {
        @JvmStatic @BeforeClass
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Runs [block] off the main thread, as Room wants. */
    private fun <T> bg(block: suspend () -> T): T =
        java.util.concurrent.Executors.newSingleThreadExecutor().submit(java.util.concurrent.Callable { runBlocking { block() } }).get()

    private fun seed(): Long {
        val lib = context.library
        val dao = lib.dao
        val albumId = dao.insertAlbum(AlbumEntity(title = "Nani's red album", createdAt = 1234L, dateLabel = "1978", dateFrom = 0, dateTo = 365))
        for (i in 0 until 3) {
            val dir = "cap_$i"
            File(lib.root, dir).mkdirs()
            val bmp = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt() + i * 40) }
            File(File(lib.root, dir), "print_1.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(File(lib.root, dir), "original.jpg").writeBytes(ByteArray(100) { it.toByte() })
            File(File(lib.root, dir), "print_1_e1.png").writeBytes(ByteArray(10))
            File(File(lib.root, dir), "story_1.m4a").writeBytes(ByteArray(30))
            val id = dao.insertPrint(
                PrintEntity(
                    albumId = albumId, scanDir = dir, fileName = "print_1.png", page = i + 1, position = 0, width = 60, height = 40,
                    sharpness = 200.0, quad = "0,0,60,0,60,40,0,40", enhance = 0, place = "Shimla", caption = "Caption $i", createdAt = 10L + i,
                ),
            )
            dao.setPeople(id, listOf("Nani", "Ravi"))
            if (i == 0) dao.insertStory(StoryEntity(printId = id, file = "story_1.m4a", durationMs = 3000, createdAt = 5))
        }
        return albumId
    }

    private fun pack(albumId: Long): ByteArray = ByteArrayOutputStream().also { out -> bg { context.albumFile.write(albumId, out) } }.toByteArray()

    @Test
    fun comesBackWhole() {
        val albumId = bg { seed() }
        val bytes = pack(albumId)
        val names = java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue("album.json" in names)
        assertTrue("scans/cap_0/print_1.png" in names)
        assertTrue("scans/cap_0/original.jpg" in names)
        assertFalse("enhanced looks are made again, not sent", names.any { it.endsWith("_e1.png") })

        bg { context.library.deleteAlbum(albumId) }
        val added = bg { context.albumFile.open(ByteArrayInputStream(bytes)) }
        assertFalse(added.alreadyHere)
        assertEquals(3, added.newPhotos)
        val dao = context.library.dao
        val prints = bg { dao.printsInNow(added.albumId) }
        assertEquals(listOf("Caption 0", "Caption 1", "Caption 2"), prints.map { it.caption })
        assertEquals(setOf("Nani", "Ravi"), bg { dao.peopleInNow(prints[0].id) }.toSet())
        assertEquals(1, bg { dao.storiesOfNow(prints[0].id) }.size)
        assertEquals("1978", bg { dao.album(added.albumId) }!!.dateLabel)
    }

    @Test
    fun aSecondCopyOnlyFillsIn() {
        val albumId = bg { seed() }
        val bytes = pack(albumId)
        val dao = context.library.dao
        // Here, someone cleared a caption and changed a place since the copy was made.
        val first = bg { dao.printsInNow(albumId) }.first()
        bg { dao.updatePrint(first.copy(caption = null, place = "Simla")) }
        val again = bg { context.albumFile.open(ByteArrayInputStream(bytes)) }
        assertTrue(again.alreadyHere)
        assertEquals(0, again.newPhotos)
        assertEquals(1, again.filledIn)
        val now = bg { dao.printsInNow(albumId) }
        assertEquals(3, now.size)
        assertEquals("Caption 0", now.first().caption)
        assertEquals("what was here stays", "Simla", now.first().place)
        // And once more: nothing new at all.
        val third = bg { context.albumFile.open(ByteArrayInputStream(bytes)) }
        assertEquals(0, third.newPhotos + third.filledIn)
    }

    @Test
    fun nothingIsWrittenOutsideItsFolder() {
        val evil = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("scans/../../escaped.txt")); z.write(1); z.closeEntry()
                z.putNextEntry(ZipEntry("album.json")); z.write("""{"format":"attic-album","albums":[{"title":"X","createdAt":1,"prints":[]}]}""".toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        bg { context.albumFile.open(ByteArrayInputStream(evil)) }
        assertFalse(File(context.cacheDir.parentFile, "escaped.txt").exists())
        assertFalse(File(context.cacheDir, "escaped.txt").exists())
    }

    @Test(expected = java.util.concurrent.ExecutionException::class)
    fun notAnAlbum() {
        bg { context.albumFile.open(ByteArrayInputStream("hello".toByteArray())) }
    }
}

class KeepsakeNameTest {
    @org.junit.Test
    fun numberedAndSafeOnAnyStick() {
        org.junit.Assert.assertEquals("03 Nani's red album.html", io.github.darkxace01.attic.data.Keepsake.fileName(3, "Nani's red album"))
        org.junit.Assert.assertEquals("12 Goa  trip 1987.html".replace("  ", " "), io.github.darkxace01.attic.data.Keepsake.fileName(12, "Goa / trip: 1987"))
        org.junit.Assert.assertEquals("01 Album.html", io.github.darkxace01.attic.data.Keepsake.fileName(1, "???"))
    }
}
