package io.github.darkxace01.attic.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.darkxace01.attic.data.AlbumEntity
import io.github.darkxace01.attic.data.FaceEntity
import io.github.darkxace01.attic.data.PrintEntity
import io.github.darkxace01.attic.library
import io.github.darkxace01.attic.ui.theme.AtticTheme
import io.github.darkxace01.attic.ui.theme.Palette
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/**
 * The app's screens, filled with the Unsplash test photos (never anyone's own), to check the design.
 * Run with -Proborazzi.test.record=true to write build/shots/app_*.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class AppShots {
    companion object {
        @JvmStatic @org.junit.BeforeClass
        fun loadOpenCv() = nu.pattern.OpenCV.loadLocally()
    }

    @get:Rule val compose = createComposeRule()

    private val photoDir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** Three albums of test photos, with dates, places and a few names. Returns the first print's id. */
    private fun seed(): Long {
        assumeTrue(photoDir.isDirectory)
        return java.util.concurrent.Executors.newSingleThreadExecutor().submit(java.util.concurrent.Callable { seedNow() }).get()
    }

    private fun seedNow(): Long {
        val lib = context.library
        val dao = lib.dao
        val files = photoDir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(84)
        val albums = listOf(
            Triple("Nani's red album", 1978, 9),
            Triple("Shimla, summer 87", 1987, 6),
            Triple("Holi at home", 1994, 5),
        )
        var k = 0
        var first = 0L
        val names = listOf("Nani", "Ravi", "Meera", "Kabir")
        for ((ai, a) in albums.withIndex()) {
            val (title, year, n) = a
            val albumId = dao.insertAlbum(AlbumEntity(title = title, createdAt = 1000L - ai, dateFrom = LocalDate.of(year, 1, 1).toEpochDay(), dateTo = LocalDate.of(year, 12, 31).toEpochDay(), dateLabel = "$year"))
            for (i in 0 until n) {
                val src = files[k++]
                val dir = "seed_$k"
                File(lib.root, dir).mkdirs()
                val bmp = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: continue
                File(File(lib.root, dir), "print_1.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                val day = LocalDate.of(year, 1 + i % 12, 1 + i)
                val id = dao.insertPrint(
                    PrintEntity(
                        albumId = albumId, scanDir = dir, fileName = "print_1.jpg", page = 1 + i / 2, position = i % 2,
                        width = bmp.width, height = bmp.height, sharpness = 200.0, quad = "0,0,1,0,1,1,0,1", enhance = 0,
                        dateFrom = day.toEpochDay(), dateTo = day.toEpochDay(), dateLabel = "${day.dayOfMonth} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)} $year",
                        place = if (i % 3 == 0) listOf("Shimla", "Jaipur", "Pune")[ai] else null,
                        caption = if (i == 0) "The first day at the new house" else null,
                        createdAt = 2000L + k,
                    ),
                )
                if (first == 0L) first = id
                dao.setPeople(id, listOf(names[(i + ai) % names.size]) + if (i % 2 == 0) listOf(names[(i + ai + 1) % names.size]) else emptyList())
            }
        }
        return first
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { AtticTheme { Box(Modifier.fillMaxSize().background(Palette.Ground)) { content() } } }
        repeat(30) { compose.mainClock.advanceTimeBy(100); Thread.sleep(40); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        compose.onRoot().captureRoboImage("build/shots/app_$name.png")
    }

    @Test fun albums() { seed(); shot("albums") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }
    @Test fun album() { seed(); shot("album") { io.github.darkxace01.attic.albums.AlbumScreen(1, onBack = {}, onOpenPrint = {}, onScanHere = {}, onTellStories = {}) } }
    @Test fun print() { val id = seed(); shot("print") { io.github.darkxace01.attic.print.PrintScreen(id, {}, {}, {}, {}, {}) } }
    @Test fun timeline() { seed(); shot("timeline") { io.github.darkxace01.attic.timeline.TimelineScreen(onOpenPrint = {}) } }
    @Test fun people() { seed(); shot("people") { io.github.darkxace01.attic.people.PeopleScreen(onOpenPerson = {}) } }
    @Test fun person() { seed(); shot("person") { io.github.darkxace01.attic.people.PersonScreen(1, onBack = {}, onOpenPrint = {}) } }
    @Test fun settings() { seed(); shot("settings") { io.github.darkxace01.attic.settings.SettingsScreen(onBack = {}) } }
    @Test fun welcome() { shot("welcome") { io.github.darkxace01.attic.AtticApp() } }
    @Test fun emptyAlbums() { shot("empty") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }
}
