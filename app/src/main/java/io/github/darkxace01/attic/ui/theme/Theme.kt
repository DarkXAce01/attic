package io.github.darkxace01.attic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import io.github.darkxace01.attic.R

/**
 * Attic in Riso: the app is a little risograph print shop in the attic. Plain paper, flat spot inks
 * (see [Ink]) that overprint where they meet, tone made of dots, and the studio's grease pencil red for
 * marks and the one main action on a screen. The photos themselves are never touched.
 */
object Palette {
    /** The theme (see [Style]); every colour below follows it, and anything drawn with them is redrawn when it changes. */
    var style by mutableStateOf(Style.Riso)

    /** The theme's dark side (Midnight, Safelight, Late night, Prussian, Projector). */
    var dark by mutableStateOf(false)

    private val c get() = Sides.of(style, dark)

    /** The paper (or glass, or lightbox) everything is printed on. */
    val Ground get() = Color(c.ground)
    val Surface get() = Color(c.surface)
    val SurfaceHigh get() = Color(c.surfaceHigh)
    /** The ink all text is printed in. */
    val Ink get() = Color(c.ink)
    val Muted get() = Color(c.muted)
    /** The one main action on a screen, and "ready": the grease pencil red in Riso. */
    val Accent get() = Color(c.accent)
    val OnAccent get() = Color(c.onAccent)
    val Warning get() = Color(c.warning)
    /** The photo's own white border, when a print is shown as an object. */
    val PrintWhite get() = Color(c.printWhite)
    /** The theme's signature colour: Infrared's crimson glow, the phosphor, Kodachrome yellow. */
    val Second get() = Color(c.second)
}

/** Real risograph spot inks (their makers' names), for islands, covers and anything printed. */
enum class Ink(val label: String, val color: Color) {
    Blue("Medium blue", Color(0xFF3255A4)),
    Pink("Fluorescent pink", Color(0xFFFF48B0)),
    Teal("Teal", Color(0xFF00838A)),
    Yellow("Yellow", Color(0xFFFFE800)),
    Orange("Orange", Color(0xFFFF6C2F)),
    Green("Green", Color(0xFF00A95C)),
    Red("Bright red", Color(0xFFF15060)),
    Purple("Purple", Color(0xFF765BA7)),
    Aqua("Aqua", Color(0xFF5EC8E5)),
    Sunflower("Sunflower", Color(0xFFFFB511)),
    Burgundy("Burgundy", Color(0xFF914E72)),
    Black("Black", Color(0xFF1D1E22)),
}

object Fonts {
    private fun anybody(weight: Int, width: Float) =
        Font(R.font.anybody, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)))

    /** Anybody, condensed and heavy: Riso's titles and album names. */
    val Anybody = FontFamily(anybody(860, 64f), anybody(700, 72f), anybody(500, 80f), anybody(300, 115f))

    /** The current theme's display face, for titles and album names. */
    val Display: FontFamily get() = display(Palette.style)

    fun display(s: Style): FontFamily = when (s) {
        Style.Riso -> Anybody
        Style.Infrared -> DisplayFaces.bricolage
        Style.Broadcast -> DisplayFaces.vt323
        Style.Cyanotype -> DisplayFaces.serif
        Style.Kodachrome -> DisplayFaces.archivo
    }

    /** The weight the display face is cut in (asking for more would only fake a bold). */
    val DisplayWeight: FontWeight get() = displayWeight(Palette.style)

    fun displayWeight(s: Style): FontWeight = when (s) {
        Style.Riso -> FontWeight(860)
        Style.Infrared -> FontWeight(800)
        Style.Kodachrome -> FontWeight(900)
        else -> FontWeight.Normal
    }

    /** VT323 and Instrument Serif are set a little larger to read at the same size as the others. */
    fun displayScale(s: Style): Float = when (s) {
        Style.Broadcast -> 1.22f
        Style.Cyanotype -> 1.12f
        else -> 1f
    }

    private fun sans(weight: Int) = Font(R.font.instrument_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    /** Instrument Sans: everything you read. */
    val Body = FontFamily(sans(400), sans(500), sans(600), sans(700))

    /** Reenie Beanie: only for grease-pencil marks and names written on photos. */
    val Hand = FontFamily(Font(R.font.reenie_beanie))
}

private const val Figures = "tnum"

/**
 * A title in the theme's display face at [size] sp: italic in Cyanotype, glowing in Infrared and on the
 * late-night glass.
 */
fun displayStyle(size: Float, line: Float = size, style: Style = Palette.style): TextStyle {
    val k = Fonts.displayScale(style)
    val second = Color(Sides.of(style, Palette.dark).second)
    val glow = when (style) {
        Style.Infrared -> Shadow(second.copy(alpha = if (Palette.dark) 0.75f else 0.5f), Offset.Zero, 16f)
        Style.Broadcast -> Shadow(second.copy(alpha = 0.45f), Offset.Zero, 14f)
        else -> null
    }
    return TextStyle(
        fontFamily = Fonts.display(style), fontWeight = Fonts.displayWeight(style), fontSize = (size * k).sp, lineHeight = (line * k).sp,
        fontStyle = if (style == Style.Cyanotype) FontStyle.Italic else FontStyle.Normal,
        fontSynthesis = FontSynthesis.None, shadow = glow,
    )
}

fun atticTypography() = Typography(
    displayLarge = displayStyle(56f, 52f),
    displayMedium = displayStyle(44f, 42f),
    displaySmall = displayStyle(34f),
    headlineSmall = displayStyle(28f, 30f),
    titleLarge = displayStyle(30f, 32f),
    titleMedium = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Fonts.Body, fontSize = 16.sp, lineHeight = 23.sp, fontFeatureSettings = Figures),
    bodyMedium = TextStyle(fontFamily = Fonts.Body, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = Figures),
    labelLarge = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = Figures),
    labelMedium = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = Figures),
)

/** Grease pencil handwriting, for marks on photos and pencil-titled albums. */
val HandStyle get() = TextStyle(fontFamily = Fonts.Hand, fontSize = 26.sp, lineHeight = 26.sp, color = Palette.Accent)

@Composable
fun AtticTheme(content: @Composable () -> Unit) {
    val d = Palette.dark
    val style = Palette.style
    val scheme = if (d) darkColorScheme(
        primary = Palette.Accent, onPrimary = Palette.OnAccent,
        background = Palette.Ground, onBackground = Palette.Ink,
        surface = Palette.Surface, onSurface = Palette.Ink,
        surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.Muted,
        surfaceContainer = Palette.Surface, surfaceContainerLow = Palette.Surface,
        surfaceContainerHigh = Palette.SurfaceHigh, surfaceContainerHighest = Palette.SurfaceHigh,
        outline = Palette.SurfaceHigh, error = Palette.Warning,
    ) else lightColorScheme(
        primary = Palette.Accent, onPrimary = Palette.OnAccent,
        background = Palette.Ground, onBackground = Palette.Ink,
        surface = Palette.Surface, onSurface = Palette.Ink,
        surfaceVariant = Palette.SurfaceHigh, onSurfaceVariant = Palette.Muted,
        surfaceContainer = Palette.Surface, surfaceContainerLow = Palette.Surface,
        surfaceContainerHigh = Palette.SurfaceHigh, surfaceContainerHighest = Palette.SurfaceHigh,
        outline = Palette.SurfaceHigh, error = Palette.Warning,
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = androidx.compose.runtime.remember(style, d) { atticTypography() },
        content = content,
    )
}
