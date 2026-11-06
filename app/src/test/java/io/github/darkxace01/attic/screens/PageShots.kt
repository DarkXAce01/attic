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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A rebuilt album page, rendered with made-up prints: photos from the Unsplash set used for training (never
 * anyone's own). Look at build/shots/ after `./gradlew testDebugUnitTest --tests '*Shots*'`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PageShots {
    @get:Rule val compose = createComposeRule()

    private val photos: List<ImageBitmap> by lazy {
        val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
        dir.listFiles().orEmpty().sortedBy { it.name }.filter { it.extension == "jpg" }.take(40).mapNotNull { f ->
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }
    }

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

    private fun Modifier.androidxBackground() = background(Palette.Ground)
}
