package io.github.darkxace01.attic.ui.signature

// Grease Pencil Studio's logo: "gp" in an italic serif, circled in the red grease-pencil loop, next to
// "Grease Pencil" over a small "STUDIO". Goes at the end of every app's Settings, above the PromiseLine.
//
// Needs GreasePencil.kt (same folder) and two fonts. Copy fonts/instrument_serif.ttf,
// fonts/instrument_serif_italic.ttf and fonts/geist_mono.ttf into the app's res/font/, then:
//
//   val studioFonts = StudioFonts(
//       serif = FontFamily(
//           Font(R.font.instrument_serif, FontWeight.Normal, FontStyle.Normal),
//           Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
//       ),
//       mono = FontFamily(Font(R.font.geist_mono)),
//   )
//   StudioLockup(ink = <the app's text colour>, fonts = studioFonts)

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/** The two typefaces the studio logo is set in. */
class StudioFonts(val serif: FontFamily, val mono: FontFamily)

/** The studio's red for text of colour [ink]: light ink means a dark background. */
fun studioRedFor(ink: Color) = if (ink.luminance() > 0.5f) GreasePencil.RedOnDark else GreasePencil.Red

private val trimmed = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** The monogram on its own (e.g. a small badge). [size] is the square it fills. */
@Composable
fun StudioMonogram(ink: Color, fonts: StudioFonts, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val fontSize = with(LocalDensity.current) { (size * 0.46f).toSp() }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        GreasePencilLoop(Modifier.size(size), color = studioRedFor(ink), strokeWidth = 0.062f, animate = false)
        BasicText(
            "gp",
            style = TextStyle(
                color = ink,
                fontFamily = fonts.serif,
                fontStyle = FontStyle.Italic,
                fontSize = fontSize,
                lineHeight = 1.em,
                letterSpacing = (-0.02).em,
                lineHeightStyle = trimmed,
            ),
            // Italic letters lean right and "g" hangs low; nudge them back to the optical centre.
            modifier = Modifier.offset(x = -size * 0.02f, y = -size * 0.035f),
        )
    }
}

/** The full logo: monogram, then "Grease Pencil" over a small "STUDIO". [height] is the monogram's size. */
@Composable
fun StudioLockup(
    ink: Color,
    fonts: StudioFonts,
    modifier: Modifier = Modifier,
    height: Dp = 34.dp,
    muted: Color = ink.copy(alpha = 0.6f),
) {
    val density = LocalDensity.current
    // Read out as one name, not "gp, Grease Pencil, STUDIO".
    Row(modifier.clearAndSetSemantics { contentDescription = "Grease Pencil Studio" }, verticalAlignment = Alignment.CenterVertically) {
        StudioMonogram(ink, fonts, size = height)
        Spacer(Modifier.width(height * 0.28f))
        Column {
            BasicText(
                "Grease Pencil",
                style = TextStyle(
                    color = ink,
                    fontFamily = fonts.serif,
                    fontSize = with(density) { (height * 0.5f).toSp() },
                    lineHeight = 1.em,
                    lineHeightStyle = trimmed,
                ),
            )
            Spacer(Modifier.size(height * 0.08f))
            BasicText(
                "STUDIO",
                style = TextStyle(
                    color = muted,
                    fontFamily = fonts.mono,
                    fontSize = with(density) { (height * 0.2f).toSp() },
                    letterSpacing = 0.32.em,
                    lineHeight = 1.em,
                    lineHeightStyle = trimmed,
                ),
            )
        }
    }
}
