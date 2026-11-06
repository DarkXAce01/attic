package io.github.darkxace01.attic.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import io.github.darkxace01.attic.ui.theme.Ink
import io.github.darkxace01.attic.world.riso.IslandKind
import io.github.darkxace01.attic.world.riso.drawIsland
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
    private fun seed(more: Boolean = false): Long {
        assumeTrue(photoDir.isDirectory)
        return java.util.concurrent.Executors.newSingleThreadExecutor().submit(java.util.concurrent.Callable { seedNow(more) }).get()
    }

    private fun seedNow(more: Boolean = false): Long {
        val lib = context.library
        val dao = lib.dao
        val files = photoDir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(84)
        val albums = listOf(
            Triple("Nani's red album", 1978, 9),
            Triple("Shimla, summer 87", 1987, 6),
            Triple("Holi at home", 1994, 5),
        ) + if (more) listOf(Triple("Dad's slides", 1972, 2), Triple("School years", 1990, 2), Triple("Wedding", 1991, 3), Triple("Goa trip", 2003, 2)) else emptyList()
        var k = 0
        var first = 0L
        val names = listOf("Nani", "Ravi", "Meera", "Kabir")
        for ((ai, a) in albums.withIndex()) {
            val (title, year, n) = a
            val albumId = dao.insertAlbum(AlbumEntity(look = if (more) io.github.darkxace01.attic.world.riso.IslandLook(io.github.darkxace01.attic.world.riso.IslandKind.entries[(ai * 3 + 1) % 13], Ink.entries[ai % 11], Ink.entries[(ai + 5) % 11]).encode() else null, title = title, createdAt = 1000L - ai, dateFrom = LocalDate.of(year, 1, 1).toEpochDay(), dateTo = LocalDate.of(year, 12, 31).toEpochDay(), dateLabel = "$year"))
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
                        place = if (i % 3 == 0) listOf("Shimla", "Jaipur", "Pune")[ai % 3] else null,
                        caption = if (i == 0) "The first day at the new house" else null,
                        createdAt = 2000L + k,
                        galleryUri = "content://seed/$k", exportedVersion = 1, backedUpVersion = 1,
                    ),
                )
                if (first == 0L) first = id
                dao.setPeople(id, listOf(names[(i + ai) % names.size]) + if (i % 2 == 0) listOf(names[(i + ai + 1) % names.size]) else emptyList())
            }
        }
        return first
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIslandAt(look: io.github.darkxace01.attic.world.riso.IslandLook, photos: List<androidx.compose.ui.graphics.ImageBitmap>, x: Float, y: Float, r: Float, seed: Int) =
        drawIsland(look, photos, androidx.compose.ui.geometry.Offset(size.width * x, size.height * y), size.width * r, 2.4f + seed, seed)

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        org.robolectric.shadows.ShadowLog.stream = System.out
        // Photos load at once, so they are in the picture.
        coil3.SingletonImageLoader.setUnsafe(coil3.ImageLoader.Builder(context).coroutineContext(kotlinx.coroutines.Dispatchers.Unconfined).components { add(coil3.decode.BitmapFactoryDecoder.Factory()) }.build())
        compose.setContent { AtticTheme { Box(Modifier.fillMaxSize().background(Palette.Ground)) { content() } } }
        repeat(60) { compose.mainClock.advanceTimeBy(100); Thread.sleep(120); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        compose.onRoot().captureRoboImage("build/shots/app_$name.png")
    }

    @Test fun albums() { seed(); shot("albums") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }
    @Test fun album() { seed(); shot("album") { io.github.darkxace01.attic.albums.AlbumScreen(1, onBack = {}, onOpenPrint = {}, onScanHere = {}, onTellStories = {}) } }
    @Test fun print() { val id = seed(); shot("print") { io.github.darkxace01.attic.print.PrintScreen(id, {}, {}, {}, {}, {}) } }
    @Test fun timeline() { seed(); shot("timeline") { io.github.darkxace01.attic.timeline.TimelineScreen(onOpenPrint = {}) } }
    @Test fun people() { seed(); shot("people") { io.github.darkxace01.attic.people.PeopleScreen(onOpenPerson = {}) } }
    @Test fun person() { seed(); shot("person") { io.github.darkxace01.attic.people.PersonScreen(1, onBack = {}, onOpenPrint = {}) } }
    @Test fun settings() { seed(); shot("settings") { io.github.darkxace01.attic.settings.SettingsScreen(onBack = {}) } }
    /** The 512 px store icon, drawn from the launcher icon itself. */
    @Test fun storeIcon() {
        val d = context.getDrawable(io.github.darkxace01.attic.R.mipmap.ic_launcher)!!
        val bmp = android.graphics.Bitmap.createBitmap(512, 512, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            // Full bleed: the store rounds the corners itself. The layers are drawn 1.5x the icon, as on a phone.
            d.background?.apply { setBounds(-128, -128, 640, 640); draw(c) }
            d.foreground?.apply { setBounds(-128, -128, 640, 640); draw(c) }
        } else { d.setBounds(0, 0, 512, 512); d.draw(c) }
        File("build/shots").mkdirs()
        File("build/shots/store_icon.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    // For the store listings: phone screenshots no taller than twice their width.
    @Test @Config(qualifiers = "w411dp-h822dp-xxhdpi") fun store1Albums() { seed(); shot("store_1") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }
    @Test @Config(qualifiers = "w411dp-h822dp-xxhdpi") fun store2Album() { seed(); shot("store_2") { io.github.darkxace01.attic.albums.AlbumScreen(1, onBack = {}, onOpenPrint = {}, onScanHere = {}, onTellStories = {}) } }
    @Test @Config(qualifiers = "w411dp-h822dp-xxhdpi") fun store3Print() { val id = seed(); shot("store_3") { io.github.darkxace01.attic.print.PrintScreen(id, {}, {}, {}, {}, {}) } }
    @Test @Config(qualifiers = "w411dp-h822dp-xxhdpi") fun store4Timeline() { seed(); shot("store_4") { io.github.darkxace01.attic.timeline.TimelineScreen(onOpenPrint = {}) } }
    @Test @Config(qualifiers = "w411dp-h822dp-xxhdpi") fun store5People() { seed(); shot("store_5") { io.github.darkxace01.attic.people.PersonScreen(1, onBack = {}, onOpenPrint = {}) } }
    @Test @Config(qualifiers = "w411dp-h822dp-xxhdpi") fun store6Welcome() { shot("store_6") { io.github.darkxace01.attic.AtticApp() } }

    /** Google Play's feature graphic, 1024 by 500. */
    @Test @Config(qualifiers = "w1024dp-h500dp-mdpi") fun featureGraphic() {
        val photos = photoDir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedBy { it.name }.drop(84).take(8)
            .mapNotNull { BitmapFactory.decodeFile(it.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }.map { it.asImageBitmap() }
        shot("feature") {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().background(Palette.Ground)) {
                androidx.compose.foundation.layout.Column(Modifier.weight(1.15f).fillMaxSize().padding(androidx.compose.ui.unit.Dp(56f)), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    androidx.compose.material3.Text("Attic", color = Palette.Ink, style = androidx.compose.ui.text.TextStyle(fontFamily = io.github.darkxace01.attic.ui.theme.Fonts.Display, fontWeight = androidx.compose.ui.text.font.FontWeight(860), fontSize = androidx.compose.ui.unit.TextUnit(110f, androidx.compose.ui.unit.TextUnitType.Sp)))
                    androidx.compose.material3.Text("Bring the old photos home.", color = Palette.Ink, style = androidx.compose.ui.text.TextStyle(fontFamily = io.github.darkxace01.attic.ui.theme.Fonts.Body, fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp)))
                    androidx.compose.material3.Text("No internet. No account. No ads.", color = Palette.Accent, style = androidx.compose.ui.text.TextStyle(fontFamily = io.github.darkxace01.attic.ui.theme.Fonts.Hand, fontSize = androidx.compose.ui.unit.TextUnit(34f, androidx.compose.ui.unit.TextUnitType.Sp)))
                }
                androidx.compose.foundation.Canvas(Modifier.weight(1f).fillMaxSize()) {
                    drawIslandAt(io.github.darkxace01.attic.world.riso.IslandLook(IslandKind.Kite, Ink.Teal, Ink.Pink, extra = io.github.darkxace01.attic.world.riso.Extra.Birds), photos, 0.30f, 0.42f, 0.17f, 1)
                    drawIslandAt(io.github.darkxace01.attic.world.riso.IslandLook(IslandKind.Balloon, Ink.Sunflower, Ink.Blue), photos.drop(3), 0.72f, 0.36f, 0.16f, 2)
                    drawIslandAt(io.github.darkxace01.attic.world.riso.IslandLook(IslandKind.Book, Ink.Red, Ink.Aqua), photos.drop(5), 0.55f, 0.74f, 0.14f, 3)
                }
            }
        }
    }

    @Test fun ring() { seed(more = true); context.library.settings.update { it.copy(albumLayout = "ring") }; shot("ring") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }
    @Test fun map() { seed(more = true); context.library.settings.update { it.copy(albumLayout = "map") }; shot("map") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun screenShot(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { AtticTheme { Box(Modifier.fillMaxSize().background(Palette.Ground)) { content() } } }
        repeat(20) { compose.mainClock.advanceTimeBy(100); Thread.sleep(40); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/shots/app_$name.png")
    }

    @Test fun sheet() = screenShot("sheet") {
        io.github.darkxace01.attic.ui.DateSheet("When was this taken?", io.github.darkxace01.attic.data.PhotoDate.parse("summer 1987"), inherited = null, onDismiss = {}, onSave = {})
    }

    @Test fun dialog() = screenShot("dialog") {
        io.github.darkxace01.attic.ui.theme.InkDialog(
            onDismissRequest = {},
            title = { androidx.compose.material3.Text("Delete this photo?") },
            text = { androidx.compose.material3.Text("The scan is deleted from Attic and your gallery. The paper print is fine, so you can scan it again.") },
            confirmButton = { io.github.darkxace01.attic.ui.QuietButton("Delete", color = Palette.Warning, onClick = {}) },
            dismissButton = { io.github.darkxace01.attic.ui.QuietButton("Keep", onClick = {}) },
        )
    }

    @Test fun welcome() { shot("welcome") { io.github.darkxace01.attic.AtticApp() } }
    @Test fun emptyAlbums() { shot("empty") { io.github.darkxace01.attic.albums.AlbumsScreen(onOpen = {}, onSettings = {}) } }
}
