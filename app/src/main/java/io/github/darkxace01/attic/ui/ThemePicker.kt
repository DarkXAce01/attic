package io.github.darkxace01.attic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.darkxace01.attic.ui.theme.Palette

/**
 * The five themes as little swatches, each in its own colours and face whatever theme is on now, so
 * you can see what you'd get before choosing it.
 */
@Composable
fun ThemePicker(chosen: io.github.darkxace01.attic.ui.theme.Style, onChoose: (io.github.darkxace01.attic.ui.theme.Style) -> Unit) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = chosen.ordinal)
    androidx.compose.runtime.LaunchedEffect(chosen) { list.animateScrollToItem((chosen.ordinal - 1).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(io.github.darkxace01.attic.ui.theme.Style.entries.size) { i ->
            val s = io.github.darkxace01.attic.ui.theme.Style.entries[i]
            val on = s == chosen
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .then(Modifier.width(132.dp))
                    .clip(shape)
                    .border(if (on) 2.5.dp else 1.dp, if (on) Palette.Ink else Palette.SurfaceHigh, shape)
                    .clickable(enabled = !on) { onChoose(s) },
            ) {
                ThemeSwatch(s, Modifier.fillMaxWidth().height(84.dp))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(s.label, style = MaterialTheme.typography.labelLarge, color = Palette.Ink)
                    Text("${s.light} and ${s.dark}", style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(chosen.about, style = MaterialTheme.typography.bodyMedium, color = Palette.Muted)
}

/** A theme's two sides split corner to corner, its title face on top and its main colour as a dot. */
@Composable
fun ThemeSwatch(s: io.github.darkxace01.attic.ui.theme.Style, modifier: Modifier) {
    val light = io.github.darkxace01.attic.ui.theme.Sides.of(s, false)
    val dark = io.github.darkxace01.attic.ui.theme.Sides.of(s, true)
    Box(modifier) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(androidx.compose.ui.graphics.Color(light.ground))
            val half = androidx.compose.ui.graphics.Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(half, androidx.compose.ui.graphics.Color(dark.ground))
            drawCircle(androidx.compose.ui.graphics.Color(light.accent), size.height * 0.13f, androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.74f))
            drawCircle(androidx.compose.ui.graphics.Color(dark.accent), size.height * 0.13f, androidx.compose.ui.geometry.Offset(size.width * 0.82f, size.height * 0.74f))
        }
        val face = io.github.darkxace01.attic.ui.theme.displayStyle(26f, style = s).copy(shadow = null)
        Text("Aa", style = face, color = androidx.compose.ui.graphics.Color(light.ink), modifier = Modifier.padding(start = 10.dp, top = 6.dp))
        Text("Aa", style = face, color = androidx.compose.ui.graphics.Color(dark.ink), modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp, top = 30.dp))
    }
}


/** The theme's two sides and Auto, as one segmented line (Auto, Paper, Midnight for Riso). */
@Composable
fun SidePicker(chosen: String, onChoose: (String) -> Unit) {
    val st = Palette.style
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clip(shape).border(1.5.dp, Palette.Ink, shape).padding(4.dp)) {
        for ((key, label) in listOf("auto" to "Auto", "paper" to st.light, "midnight" to st.dark)) {
            val on = key == chosen
            Box(
                Modifier
                    .weight(1f)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp))
                    .background(if (on) Palette.Ink else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(enabled = !on) { onChoose(key) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Palette.Ground else Palette.Ink)
            }
        }
    }
}

/**
 * A stand-in photo for previews before there are any of your own: a little sunset drawn in flat
 * colour, so a theme can be seen with a picture in it.
 */
fun samplePhoto(): androidx.compose.ui.graphics.ImageBitmap {
    val w = 240; val h = 180
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val bands = listOf(0xFF2E3A6E, 0xFF6B4C8A, 0xFFC0607A, 0xFFF0946A, 0xFFF7C98A)
    for ((i, col) in bands.withIndex()) { p.color = col.toInt(); c.drawRect(0f, i * 22f, w.toFloat(), (i + 1) * 22f + 1, p) }
    p.color = 0xFFFFE2A8.toInt(); c.drawCircle(150f, 104f, 26f, p)
    p.color = 0xFF3B2A44.toInt()
    c.drawPath(android.graphics.Path().apply { moveTo(0f, 120f); quadTo(60f, 86f, 120f, 116f); quadTo(180f, 92f, 240f, 118f); lineTo(240f, 180f); lineTo(0f, 180f); close() }, p)
    p.color = 0xFF22182C.toInt()
    c.drawPath(android.graphics.Path().apply { moveTo(0f, 146f); quadTo(90f, 128f, 240f, 150f); lineTo(240f, 180f); lineTo(0f, 180f); close() }, p)
    return bmp.asImageBitmap()
}

