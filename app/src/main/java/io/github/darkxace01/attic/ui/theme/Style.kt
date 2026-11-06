package io.github.darkxace01.attic.ui.theme

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.darkxace01.attic.R

/**
 * Attic's themes. Each is a whole way of printing the app, with a light side and a dark side (named
 * for what they are, not "light" and "dark"). The photos are never touched by any of them: a theme
 * only changes what's drawn round a photo.
 */
enum class Style(val label: String, val light: String, val dark: String, val about: String) {
    Riso("Riso", "Paper", "Midnight", "Spot inks on plain paper, overprinted, tone in dots."),
    Infrared("Infrared", "Bloom", "Safelight", "Infrared film: leaves turn crimson, skies cyan, and bright things glow."),
    Broadcast("Night broadcast", "Test card", "Late night", "An old television after hours: phosphor lines on glass, colour fringes."),
    Cyanotype("Cyanotype", "Sun print", "Prussian", "A sun print: white lines where the light got through, deep Prussian blue."),
    Kodachrome("Kodachrome", "Lightbox", "Projector", "Slide film on a lightbox: warm, saturated, the yellow box and red letters."),
    ;

    companion object {
        fun parse(s: String?): Style = entries.firstOrNull { it.name == s } ?: Riso
    }
}

/** Every colour a theme side prints the app's own surfaces in. [second] is its signature colour (glow, phosphor, fringe). */
class Colors(
    val ground: Long, val surface: Long, val surfaceHigh: Long, val ink: Long, val muted: Long,
    val accent: Long, val onAccent: Long, val warning: Long, val printWhite: Long, val second: Long,
)

internal object Sides {
    private val riso = Colors(0xFFEDEDE9, 0xFFE2E2DD, 0xFFD4D4CE, 0xFF1D1E22, 0xFF5F6068, 0xFFD4432A, 0xFFFFFFFF, 0xFFB3261E, 0xFFF4F2ED, 0xFF3255A4) to
        Colors(0xFF14172E, 0xFF1C2040, 0xFF282D56, 0xFFEEEAE0, 0xFFA3A6C4, 0xFFE5553A, 0xFFFFFFFF, 0xFFFF7A6B, 0xFFF4F2ED, 0xFF3255A4)
    private val infrared = Colors(0xFFF6ECEE, 0xFFFBF4F5, 0xFFEBD3DA, 0xFF3A0D1C, 0xFF8A5763, 0xFF008FA3, 0xFFFFFFFF, 0xFFB3261E, 0xFFFFF9F6, 0xFFD3164F) to
        Colors(0xFF170910, 0xFF24101A, 0xFF3A0F1F, 0xFFFBE3EA, 0xFFC58C9C, 0xFF21D4E8, 0xFF06181B, 0xFFFF7A6B, 0xFFF7F1EC, 0xFFFF2A63)
    private val broadcast = Colors(0xFFD5D6D2, 0xFFE6E7E3, 0xFFC4C5C0, 0xFF121316, 0xFF55585F, 0xFFD21F25, 0xFFFFFFFF, 0xFFB3261E, 0xFFF4F2ED, 0xFF121316) to
        Colors(0xFF070B10, 0xFF0E151C, 0xFF1A2530, 0xFFDFF5E3, 0xFF7FA08A, 0xFFFFB000, 0xFF1A1000, 0xFFFF7A6B, 0xFFF4F2ED, 0xFF9DFFB8)
    private val cyanotype = Colors(0xFFF5F3EC, 0xFFFFFFFF, 0xFFE3E6EE, 0xFF14306E, 0xFF5B6B91, 0xFF1F4CB0, 0xFFFFFFFF, 0xFFB3261E, 0xFFFFFFFF, 0xFF14306E) to
        Colors(0xFF102A63, 0xFF173375, 0xFF22408A, 0xFFEEF2FA, 0xFF9FB0D6, 0xFFF2C14E, 0xFF1B1300, 0xFFFF9C8F, 0xFFF4F2ED, 0xFFEEF2FA)
    private val kodachrome = Colors(0xFFFBF6EA, 0xFFFFFDF6, 0xFFEFE5CF, 0xFF2A1A12, 0xFF7A6455, 0xFFD6282B, 0xFFFFFFFF, 0xFFB3261E, 0xFFFFFFFF, 0xFFF7B500) to
        Colors(0xFF1B1410, 0xFF261C16, 0xFF3A2C22, 0xFFFBEFD9, 0xFFBFA88E, 0xFFF7B500, 0xFF1B1300, 0xFFFF7A6B, 0xFFF4EEE2, 0xFFF7B500)

    fun of(style: Style, dark: Boolean): Colors {
        val pair = when (style) {
            Style.Riso -> riso; Style.Infrared -> infrared; Style.Broadcast -> broadcast
            Style.Cyanotype -> cyanotype; Style.Kodachrome -> kodachrome
        }
        return if (dark) pair.second else pair.first
    }
}

/** Each theme's display face: the one for titles, album names and big numbers. */
internal object DisplayFaces {
    val bricolage = FontFamily(Font(R.font.bricolage, FontWeight(800)))
    val vt323 = FontFamily(Font(R.font.vt323, FontWeight.Normal))
    val serif = FontFamily(Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal), Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic))
    val archivo = FontFamily(Font(R.font.archivo_condensed, FontWeight(900)))
}

/**
 * The shadow a card, print or sheet casts in the current theme, drawn behind [size] at [topLeft]:
 * Riso's dot screen a little off register, Infrared's halation glow, the phosphor's glow round glass,
 * the cyanotype's plain line, Kodachrome's soft lightbox shadow. [lift] is how far it stands off the page.
 */
fun DrawScope.themeShadow(topLeft: Offset, size: Size, corner: Float = 0f, lift: Float = 6f, fill: Color = Palette.PrintWhite) {
    val d = density
    when (Palette.style) {
        Style.Riso -> drawRoundRect(Riso.dots(Palette.Ink, 3 * d, 0.5f), topLeft + Offset(lift, lift), size, CornerRadius(corner))
        Style.Cyanotype -> drawRoundRect(Palette.Ink, topLeft, size, CornerRadius(corner), style = Stroke(1.5f * d))
        else -> {
            val (color, blur, dy) = when (Palette.style) {
                Style.Infrared -> Triple(Palette.Second.copy(alpha = if (Palette.dark) 0.75f else 0.5f), 14f * d, 0f)
                Style.Broadcast -> Triple(Palette.Second.copy(alpha = if (Palette.dark) 0.4f else 0.3f), 10f * d, 0f)
                else -> Triple(Color(0xFF3C1E0A).copy(alpha = if (Palette.dark) 0.6f else 0.28f), 8f * d, 3f * d)
            }
            glowRect(topLeft, size, corner, fill, color, blur, dy)
        }
    }
}

/** [fill] in a rounded rect with a blurred shadow of [glow] under it (a real shadow layer, not a gradient). */
fun DrawScope.glowRect(topLeft: Offset, size: Size, corner: Float, fill: Color, glow: Color, blur: Float, dy: Float = 0f) {
    drawIntoCanvas { c ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = fill.toArgb()
            setShadowLayer(blur, 0f, dy, glow.toArgb())
        }
        c.nativeCanvas.drawRoundRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height, corner, corner, p)
    }
}

/** The same for a circle (the shutter, monograms). */
fun DrawScope.glowCircle(center: Offset, r: Float, fill: Color, glow: Color, blur: Float, dy: Float = 0f) {
    drawIntoCanvas { c ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = fill.toArgb()
            setShadowLayer(blur, 0f, dy, glow.toArgb())
        }
        c.nativeCanvas.drawCircle(center.x, center.y, r, p)
    }
}

/**
 * The ground under every screen. Night broadcast draws its scanlines and the glass's dark corners here,
 * on the app's own paper, so they sit under every photo and never across one.
 */
fun DrawScope.themeGround() {
    drawRect(Palette.Ground)
    if (Palette.style == Style.Broadcast) {
        val step = 3 * density
        val line = Color.Black.copy(alpha = if (Palette.dark) 0.34f else 0.06f)
        var y = 0f
        while (y < size.height) { drawRect(line, Offset(0f, y + step * 2 / 3), Size(size.width, step / 3)); y += step }
        // Dark corners: four soft shadows cast inward from just outside the glass.
        val edge = 40 * density
        val shade = Color.Black.copy(alpha = if (Palette.dark) 0.55f else 0.22f)
        drawIntoCanvas { c ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = edge
                color = shade.toArgb()
                maskFilter = android.graphics.BlurMaskFilter(edge, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            c.nativeCanvas.drawRoundRect(-edge / 2, -edge / 2, size.width + edge / 2, size.height + edge / 2, edge, edge, p)
        }
    }
}

/** A screen's ground in the current theme (see [themeGround]). */
fun androidx.compose.ui.Modifier.ground(): androidx.compose.ui.Modifier = this.drawBehind { themeGround() }
