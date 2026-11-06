package io.github.darkxace01.attic.world.riso

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.darkxace01.attic.ui.theme.Fonts
import io.github.darkxace01.attic.ui.theme.Palette
import java.io.File

/** An album in the world: its look, a few of its photos, and what's written beside it. */
class WorldIsland(val id: Long, val title: String, val meta: String, val look: IslandLook, val photos: List<ImageBitmap>)

/**
 * The albums as islands on Riso paper, one after another down the page, left and right in turn, each
 * with its title beside it. Tap to open; hold to make it yours. [time] drives every motion.
 */
@Composable
fun RisoWorld(
    islands: List<WorldIsland>,
    time: () -> Float,
    onOpen: (Long) -> Unit,
    onDress: (Long) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    LazyColumn(modifier.fillMaxSize().background(Palette.Ground), contentPadding = PaddingValues(bottom = 40.dp)) {
        item { header() }
        itemsIndexed(islands, key = { _, w -> w.id }) { i, w -> IslandRow(w, i, time, onOpen, onDress) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IslandRow(w: WorldIsland, index: Int, time: () -> Float, onOpen: (Long) -> Unit, onDress: (Long) -> Unit) {
    val left = index % 2 == 0
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    // Pressing sinks it into the paper a little; no bounce.
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(120), label = "press")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .combinedClickable(interactionSource = press, indication = null, onClick = { onOpen(w.id) }, onLongClick = { onDress(w.id) }),
    ) {
        val wpx = constraints.maxWidth.toFloat()
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
            drawIsland(w.look, w.photos, Offset(wpx * if (left) 0.38f else 0.62f, size.height * 0.5f), wpx * 0.25f, time(), index)
        }
        AlbumTitle(
            w.title, w.meta, w.look.title,
            Modifier
                .align(if (left) Alignment.BottomEnd else Alignment.BottomStart)
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .widthIn(max = 170.dp),
            alignEnd = left,
        )
    }
}

/** The title set the way the album's owner chose: printed, in grease pencil, or on label tape. */
@Composable
fun AlbumTitle(title: String, meta: String, style: TitleStyle, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    val align = if (alignEnd) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        when (style) {
            TitleStyle.Printed -> Text(
                title, textAlign = align, color = Palette.Ink,
                style = TextStyle(fontFamily = Fonts.Display, fontWeight = FontWeight(860), fontSize = 28.sp, lineHeight = 27.sp),
            )
            TitleStyle.Pencil -> Text(
                title, textAlign = align, color = Palette.Accent,
                style = TextStyle(fontFamily = Fonts.Hand, fontSize = 36.sp, lineHeight = 32.sp),
            )
            TitleStyle.Tape -> Text(
                title.uppercase(), color = Palette.Ground,
                style = TextStyle(fontFamily = Fonts.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.2.sp),
                modifier = Modifier.graphicsLayer { rotationZ = -2f }.background(Palette.Ink).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(meta, textAlign = align, color = Palette.Muted, style = TextStyle(fontFamily = Fonts.Body, fontSize = 13.sp, lineHeight = 18.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

/** Decodes [f] at about [side] pixels on its longer side, for drawing on an island. */
fun islandBitmap(f: File, side: Int = 360): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    val longer = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longer / (sample * 2) >= side) sample *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}
