package io.github.darkxace01.attic.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** The handle on top of a sheet: a short stroke of grease pencil instead of a grey pill. */
@Composable
fun PencilHandle() {
    Canvas(Modifier.padding(top = 12.dp, bottom = 18.dp).size(40.dp, 8.dp)) {
        drawLine(Palette.Ink, Offset(2.dp.toPx(), size.height * 0.62f), Offset(size.width - 2.dp.toPx(), size.height * 0.38f), 3.dp.toPx(), StrokeCap.Round)
    }
}

/**
 * A question or a warning, printed on a card: paper, an ink outline and a dot-screen shadow a little off,
 * like everything else in Attic. Takes the same parts as Material's AlertDialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") containerColor: Color = Palette.Ground,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest, modifier = modifier) {
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .padding(horizontal = 12.dp)
                .padding(end = 8.dp, bottom = 8.dp)
                .drawBehind {
                    val s = 8.dp.toPx()
                    if (Palette.style == Style.Riso) drawRoundRect(Riso.dots(Palette.Ink, 3.dp.toPx(), 0.5f), topLeft = Offset(s, s), size = size, cornerRadius = CornerRadius(18.dp.toPx()))
                    else themeShadow(Offset.Zero, size, 18.dp.toPx(), 0f, Palette.Ground)
                }
                .background(Palette.Ground, shape)
                .border(if (Palette.style == Style.Riso) 2.dp else if (Palette.style == Style.Cyanotype) 1.5.dp else 0.dp, if (Palette.style == Style.Riso || Palette.style == Style.Cyanotype) Palette.Ink else Color.Transparent, shape)
                .padding(start = 22.dp, end = 12.dp, top = 22.dp, bottom = 10.dp),
        ) {
            title?.let {
                Box(Modifier.padding(end = 10.dp)) {
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Ink) {
                        ProvideTextStyle(MaterialTheme.typography.headlineSmall) { it() }
                    }
                }
            }
            if (title != null && text != null) Spacer(Modifier.height(10.dp))
            text?.let {
                Box(Modifier.padding(end = 10.dp)) {
                    CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Muted) {
                        ProvideTextStyle(MaterialTheme.typography.bodyLarge) { it() }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                dismissButton?.invoke()
                confirmButton()
            }
        }
    }
}
