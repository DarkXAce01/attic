package io.github.darkxace01.attic.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A heading printed twice, the Riso way: once in a spot ink, then in black a hair off register over it,
 * so a sliver of colour shows along every letter.
 */
@Composable
fun OverprintText(
    text: String,
    style: TextStyle,
    ink: Color,
    modifier: Modifier = Modifier,
    by: Dp = 2.dp,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    Box(modifier) {
        Text(text, style = style, color = ink, maxLines = maxLines, textAlign = textAlign, modifier = Modifier.offset(by, by))
        Text(text, style = style, color = Palette.Ink, maxLines = maxLines, textAlign = textAlign)
    }
}

/**
 * A photo shown as a print: a white border round it and a dot-screen shadow printed behind, a little off.
 * Only the card is printed; the photo inside is never touched.
 */
fun Modifier.printCard(border: Dp = 5.dp, shadow: Dp = 6.dp, shadowInk: Color = Palette.Ink): Modifier = this
    .drawBehind {
        val s = shadow.toPx()
        drawRect(Riso.dots(shadowInk, 3.dp.toPx(), 0.5f), topLeft = Offset(s, s), size = size)
    }
    .background(Palette.PrintWhite)
    .padding(border)

/** An on and off switch in black ink on paper: the pencil red is kept for the one main thing on a screen. */
@Composable
fun InkSwitch(checked: Boolean, onChange: (Boolean) -> Unit, onDark: Boolean = false) {
    val ink = if (onDark) Palette.Ground else Palette.Ink
    val paper = if (onDark) Palette.Ink else Palette.Ground
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedTrackColor = ink, checkedThumbColor = paper, checkedBorderColor = ink,
            uncheckedTrackColor = paper, uncheckedThumbColor = ink, uncheckedBorderColor = ink,
        ),
    )
}
