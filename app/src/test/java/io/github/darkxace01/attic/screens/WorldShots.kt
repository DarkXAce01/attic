package io.github.darkxace01.attic.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.background
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.darkxace01.attic.ui.theme.AtticTheme
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.world.AlbumWorld
import io.github.darkxace01.attic.world.CLOTHS
import io.github.darkxace01.attic.world.Island
import io.github.darkxace01.attic.world.WorldAlbum
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The album world, rendered with made-up albums: photos from the Unsplash set used for training (never
 * anyone's own). Look at build/shots/ after `./gradlew testDebugUnitTest --tests '*Shots*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class WorldShots {
    @get:Rule val compose = createComposeRule()

    private val photos: List<ImageBitmap> by lazy {
        val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
        dir.listFiles().orEmpty().sortedBy { it.name }.filter { it.extension == "jpg" }.take(40).mapNotNull { f ->
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }
    }

    private fun albums(): List<WorldAlbum> {
        val titles = listOf("Nani's red album" to "1978 to 1986 · 112 photos", "Shimla, summer 87" to "1987 · 36 photos",
            "Wedding" to "1991 · 64 photos", "School years" to "1990s · 48 photos", "Dad's slides" to "1970s · 20 photos")
        return titles.mapIndexed { i, (t, m) ->
            WorldAlbum(
                Island(
                    key = i.toLong() + 1, cover = photos.getOrNull(i * 5), satellites = photos.drop(i * 5 + 1).take(3),
                    pages = listOf(30, 8, 18, 12, 4)[i], cloth = CLOTHS[i % CLOTHS.size], mirrored = i % 2 == 1,
                    aspect = if (i == 3) 0.95f else 0.74f,
                ),
                t, m,
            )
        }
    }

    @Test
    fun opening() {
        val island = albums()[0].island
        compose.setContent {
            androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.fillMaxSize()) {
                drawRect(io.github.darkxace01.attic.world.ATTIC)
                val cells = listOf(0f, 0.35f, 0.7f, 1f)
                for ((k, o) in cells.withIndex()) {
                    val cx = size.width * (0.25f + 0.5f * (k % 2))
                    val cy = size.height * (0.25f + 0.5f * (k / 2))
                    io.github.darkxace01.attic.world.drawIslandForTest(this, island, androidx.compose.ui.geometry.Offset(cx, cy), size.width * (0.36f + 0.1f * o), 0f, o)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/shots/opening.png")
    }

    @Test
    fun world() {
        val list = albums()
        for (t in listOf(0f, 7f)) {
            compose.setContent {
                AtticTheme {
                    AlbumWorld(list, time = { t }, onOpen = {}, header = {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                            Text("Attic", color = Palette.Ink)
                        }
                    })
                }
            }
            compose.onRoot().captureRoboImage("build/shots/world_t${t.toInt()}.png")
            break
        }
    }

    /** A page scanned from a photo: a dark album page with three prints stuck on it, a little crooked. */
    @Test
    fun page() {
        val W = 1600; val H = 1200
        val bmp = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF5B4636.toInt())
        // Uneven light, the way a phone photo of a page has it.
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(W * 0.3f, H * 0.3f, W * 0.9f, 0x22FFFFFF, 0x33000000, android.graphics.Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), paint)
        val placed = listOf(
            floatArrayOf(330f, 360f, 420f, 300f, -3f),
            floatArrayOf(870f, 380f, 300f, 400f, 2f),
            floatArrayOf(560f, 850f, 440f, 300f, 1f),
        )
        val quads = mutableListOf<io.github.darkxace01.attic.vision.Quad>()
        placed.forEachIndexed { i, (cx, cy, w, h, a) ->
            val photo = photos[10 + i].asAndroidBitmap()
            c.save(); c.rotate(a, cx, cy)
            c.drawBitmap(photo, null, android.graphics.RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), null)
            c.restore()
            val r = Math.toRadians(a.toDouble())
            fun pt(dx: Float, dy: Float) = io.github.darkxace01.attic.vision.Pt(
                cx + (dx * Math.cos(r) - dy * Math.sin(r)).toFloat(), cy + (dx * Math.sin(r) + dy * Math.cos(r)).toFloat())
            quads += io.github.darkxace01.attic.vision.Quad(pt(-w / 2, -h / 2), pt(w / 2, -h / 2), pt(w / 2, h / 2), pt(-w / 2, h / 2))
        }
        val frame = io.github.darkxace01.attic.vision.PageLayout.frame(quads, W.toFloat(), H.toFloat())
        val model = io.github.darkxace01.attic.world.PageModel(
            3, bmp.asImageBitmap(), frame,
            quads.mapIndexed { i, q -> io.github.darkxace01.attic.world.PagePrint(i.toLong(), photos[10 + i], io.github.darkxace01.attic.vision.PageLayout.spot(q, frame)) },
            frame.width / frame.height,
        )
        compose.setContent {
            AtticTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().androidxBackground()) {
                    io.github.darkxace01.attic.world.RebuiltPage(model, onOpenPrint = {}, modifier = Modifier.fillMaxSize()) { pp, mod ->
                        androidx.compose.foundation.Image(pp.image as ImageBitmap, null, mod, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/shots/page.png")
    }

    private fun Modifier.androidxBackground() = background(io.github.darkxace01.attic.world.ATTIC)
}
