package io.github.darkxace01.attic.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app is the lightbox: true neutral greys so the photos carry all the colour.
 * One accent, used only for "ready" (a locked print, the shutter) and the main action.
 */
object Palette {
    val Ground = Color(0xFF0D0D0D)
    val Surface = Color(0xFF1A1A1A)
    val SurfaceHigh = Color(0xFF262626)
    val Ink = Color(0xFFF2F2F2)
    val Muted = Color(0xFF9E9E9E)
    val Accent = Color(0xFFFFC940)
    val OnAccent = Color(0xFF1A1400)
    val Warning = Color(0xFFFF8A65)
}

private val Figures = "tnum"

val AtticTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = Figures),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = Figures),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = Figures),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = Figures),
)

@Composable
fun AtticTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            background = Palette.Ground,
            onBackground = Palette.Ink,
            surface = Palette.Surface,
            onSurface = Palette.Ink,
            surfaceVariant = Palette.SurfaceHigh,
            onSurfaceVariant = Palette.Muted,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceHigh,
            outline = Palette.SurfaceHigh,
            error = Palette.Warning,
        ),
        typography = AtticTypography,
        content = content,
    )
}
