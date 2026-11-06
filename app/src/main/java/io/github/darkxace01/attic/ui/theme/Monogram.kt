package io.github.darkxace01.attic.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/** The inks a person's monogram can be printed in: every spot ink but black, which is for text. */
private val MonogramInks = Ink.entries.filter { it != Ink.Black }

/** The same person always gets the same ink. */
fun inkFor(name: String): Ink = MonogramInks[Math.floorMod(name.trim().lowercase().hashCode(), MonogramInks.size)]

/**
 * Someone without a face to show: their initial on a disc of spot ink, with a dot-screen shadow printed a
 * hair off register, like the islands.
 */
@Composable
fun Monogram(name: String, size: Dp, modifier: Modifier = Modifier) {
    val ink = inkFor(name).color
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2 * 0.94f
            drawCircle(Riso.dots(Palette.Ink, 3.dp(this), 0.55f), r, center + Offset(r * 0.09f, r * 0.09f))
            drawCircle(ink, r, center - Offset(r * 0.03f, r * 0.03f))
        }
        Text(
            name.trim().take(1).uppercase(),
            color = if (ink.luminance() > 0.5f) Palette.Ink else Color.White,
            style = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = (size.value * 0.44f).sp),
        )
    }
}

private fun Int.dp(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) { androidx.compose.ui.unit.Dp(this@dp.toFloat()).toPx() }
