package io.github.darkxace01.attic.world

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.darkxace01.attic.data.AlbumSummary
import io.github.darkxace01.attic.data.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Builds an album's island: its cover and a few of its photos, small, and a binding coloured after them. */
suspend fun loadIsland(library: Library, a: AlbumSummary, index: Int): Island = withContext(Dispatchers.IO) {
    val files = library.islandFiles(a.id)
    val cover = files.firstOrNull()?.let { decodeSmall(it, 420) }
    val sats = files.drop(1).mapNotNull { decodeSmall(it, 220) }
    Island(
        key = a.id,
        cover = cover,
        satellites = sats,
        pages = a.pages,
        cloth = cover?.let(::clothFor) ?: CLOTHS[(a.id % CLOTHS.size).toInt()],
        mirrored = index % 2 == 1,
        aspect = if (a.id % 4 == 3L) 0.92f else 0.74f,
    )
}

/** Decodes [f] at about [side] pixels on its longer side. */
private fun decodeSmall(f: File, side: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, bounds)
    val longer = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (longer / (sample * 2) >= side) sample *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * A binding colour after a photo: its average hue, taken down to the deep, dusty tone of book cloth, so
 * a beach album comes in sandy brown and a garden album in moss, never a bright plastic colour.
 */
fun clothFor(img: ImageBitmap): Color {
    val bmp = img.asAndroidBitmap()
    var r = 0f; var g = 0f; var b = 0f; var n = 0
    for (i in 1..7) for (j in 1..7) {
        val px = bmp.getPixel(bmp.width * i / 8, bmp.height * j / 8)
        r += android.graphics.Color.red(px); g += android.graphics.Color.green(px); b += android.graphics.Color.blue(px); n++
    }
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
    hsv[1] = (hsv[1] * 0.75f + 0.12f).coerceIn(0.18f, 0.55f)
    hsv[2] = 0.3f + 0.12f * hsv[2]
    return Color(android.graphics.Color.HSVToColor(hsv))
}
