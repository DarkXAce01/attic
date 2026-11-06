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
import androidx.compose.ui.unit.sp
import io.github.darkxace01.attic.R

/**
 * Attic in Riso: the app is a little risograph print shop in the attic. Plain paper, flat spot inks
 * (see [Ink]) that overprint where they meet, tone made of dots, and the studio's grease pencil red for
 * marks and the one main action on a screen. The photos themselves are never touched.
 */
object Palette {
    /**
     * Midnight: the same print shop at night, pale ink on a deep blue paper. Every colour below follows
     * it, and anything drawn with them is redrawn when it changes.
     */
    var dark by mutableStateOf(false)

    /** Riso paper: cool and plain (Paper); a midnight-blue sheet (Midnight). */
    val Ground get() = if (dark) Color(0xFF14172E) else Color(0xFFEDEDE9)
    val Surface get() = if (dark) Color(0xFF1C2040) else Color(0xFFE2E2DD)
    val SurfaceHigh get() = if (dark) Color(0xFF282D56) else Color(0xFFD4D4CE)
    /** The ink all text is printed in: Riso black on paper, a warm pale ink on midnight. */
    val Ink get() = if (dark) Color(0xFFEEEAE0) else Color(0xFF1D1E22)
    val Muted get() = if (dark) Color(0xFFA3A6C4) else Color(0xFF5F6068)
    /** Grease pencil: the studio red, a touch brighter on dark. */
    val Accent get() = if (dark) Color(0xFFE5553A) else Color(0xFFD4432A)
    val OnAccent = Color(0xFFFFFFFF)
    val Warning get() = if (dark) Color(0xFFFF7A6B) else Color(0xFFB3261E)
    /** The photo's own white border, when a print is shown as an object: a print is white in any light. */
    val PrintWhite = Color(0xFFF4F2ED)
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

    /** Anybody, condensed and heavy: titles and album names. */
    val Display = FontFamily(anybody(860, 64f), anybody(700, 72f), anybody(500, 80f), anybody(300, 115f))

    private fun sans(weight: Int) = Font(R.font.instrument_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    /** Instrument Sans: everything you read. */
    val Body = FontFamily(sans(400), sans(500), sans(600), sans(700))

    /** Reenie Beanie: only for grease-pencil marks and names written on photos. */
    val Hand = FontFamily(Font(R.font.reenie_beanie))
}

private const val Figures = "tnum"

val AtticTypography = Typography(
    displayLarge = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = 56.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = 44.sp, lineHeight = 42.sp),
    displaySmall = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = 34.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = 28.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = 30.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Fonts.Body, fontSize = 16.sp, lineHeight = 23.sp, fontFeatureSettings = Figures),
    bodyMedium = TextStyle(fontFamily = Fonts.Body, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = Figures),
    labelLarge = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = Figures),
    labelMedium = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = Figures),
)

/** Grease pencil handwriting, for marks on photos and pencil-titled albums. */
val HandStyle = TextStyle(fontFamily = Fonts.Hand, fontSize = 26.sp, lineHeight = 26.sp, color = Palette.Accent)

@Composable
fun AtticTheme(content: @Composable () -> Unit) {
    val d = Palette.dark
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
        typography = AtticTypography,
        content = content,
    )
}
