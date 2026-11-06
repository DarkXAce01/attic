package io.github.darkxace01.attic.screens

import io.github.darkxace01.attic.ui.theme.themeGround
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.darkxace01.attic.ui.theme.Palette
import io.github.darkxace01.attic.world.riso.Extra
import io.github.darkxace01.attic.world.riso.IslandKind
import io.github.darkxace01.attic.world.riso.IslandLook
import io.github.darkxace01.attic.world.riso.drawIsland
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/** Every kind of island, printed in Riso, with photos from the Unsplash set (never anyone's own). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class RisoShots {
    @get:Rule val compose = createComposeRule()

    private val photos: List<ImageBitmap> by lazy {
        val dir = File(System.getProperty("user.home"), ".cache/attic-ml/data/photos")
        dir.listFiles().orEmpty().sortedBy { it.name }.filter { it.extension == "jpg" }.drop(84).take(12).mapNotNull { f ->
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
        }
    }

    @Test
    fun everyKind() {
        val kinds = IslandKind.entries
        val extras = Extra.entries
        compose.setContent {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Palette.Ground)
                val cols = 3
                val rows = (kinds.size + cols - 1) / cols
                val cw = size.width / cols; val ch = size.height / rows
                for ((i, k) in kinds.withIndex()) {
                    val (a, b) = IslandLook.PAIRS[i % IslandLook.PAIRS.size]
                    val look = IslandLook(k, a, b, extra = extras[i % extras.size])
                    val c = Offset(cw * (i % cols + 0.5f), ch * (i / cols + 0.55f))
                    drawIsland(look, photos.drop(i % 6), c, cw * 0.36f, 1.3f + i, i)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/shots/riso_kinds.png")
    }

    /** Every kind in every theme, both sides: riso_kinds_<theme>_<side>.png. */
    @Test
    fun everyKindEveryTheme() {
        val kinds = IslandKind.entries
        compose.setContent {
            Canvas(Modifier.fillMaxSize()) {
                themeGround()
                val cols = 3
                val rows = (kinds.size + cols - 1) / cols
                val cw = size.width / cols; val ch = size.height / rows
                for ((i, k) in kinds.withIndex()) {
                    val (a, b) = IslandLook.PAIRS[i % IslandLook.PAIRS.size]
                    val c = Offset(cw * (i % cols + 0.5f), ch * (i / cols + 0.5f))
                    drawIsland(IslandLook(k, a, b), photos.drop(i % 6), c, cw * 0.36f, 1.3f + i, i)
                }
            }
        }
        for (style in io.github.darkxace01.attic.ui.theme.Style.entries) for (dark in listOf(false, true)) {
            Palette.style = style; Palette.dark = dark
            compose.waitForIdle()
            compose.onRoot().captureRoboImage("build/shots/riso_kinds_${style.name}_${if (dark) "dark" else "light"}.png")
        }
        Palette.style = io.github.darkxace01.attic.ui.theme.Style.Riso; Palette.dark = false
    }

    @Test
    fun world() {
        val titles = listOf("Nani's red album" to "1978 to 1986 · 112 photos", "Shimla, summer 87" to "1987 · 36 photos", "Wedding" to "1991 · 64 photos")
        val kinds = listOf(IslandKind.Branch, IslandKind.Balloon, IslandKind.Pond)
        val styles = io.github.darkxace01.attic.world.riso.TitleStyle.entries
        val islands = titles.mapIndexed { i, (t, m) ->
            val (a, b) = IslandLook.PAIRS[i]
            io.github.darkxace01.attic.world.riso.WorldIsland(i + 1L, t, m, IslandLook(kinds[i], a, b, styles[i], Extra.entries[i + 1]), photos.drop(i * 3))
        }
        compose.setContent {
            io.github.darkxace01.attic.ui.theme.AtticTheme {
                io.github.darkxace01.attic.world.riso.RisoWorld(islands, time = { 2.2f }, onOpen = {}, onDress = {}, header = {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) {
                        androidx.compose.material3.Text("Albums", style = androidx.compose.material3.MaterialTheme.typography.displayMedium, color = Palette.Ink)
                        androidx.compose.material3.Text("Hold an album to make it yours.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = Palette.Muted)
                    }
                })
            }
        }
        compose.onRoot().captureRoboImage("build/shots/riso_world.png")
    }
}
