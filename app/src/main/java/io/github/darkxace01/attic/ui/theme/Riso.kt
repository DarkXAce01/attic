package io.github.darkxace01.attic.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import kotlin.math.roundToInt

/**
 * Printing in Riso on a Compose canvas. Tone is a dot screen (no gradients anywhere); a second ink is
 * multiplied over the first, a hair off register, the way two passes through the drum land.
 */
object Riso {
    private val cache = HashMap<String, ShaderBrush>()

    /**
     * A screen of [color] dots, [cell] pixels apart, each [fill] of the cell across (0..1), the screen
     * turned [angle] degrees as printers turn each ink's screen.
     */
    fun dots(color: Color, cell: Float, fill: Float = 0.72f, angle: Float = 15f): ShaderBrush {
        val c = cell.roundToInt().coerceAtLeast(3)
        val key = "${color.toArgb()}/$c/${(fill * 100).roundToInt()}/${angle.roundToInt()}"
        return cache.getOrPut(key) {
            val bmp = Bitmap.createBitmap(c, c, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawCircle(c / 2f, c / 2f, c * fill / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() })
            val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(Matrix().apply { setRotate(angle) })
            }
            ShaderBrush(shader)
        }
    }

    /** Lines instead of dots (for water, rain and hatching). */
    fun lines(color: Color, cell: Float, weight: Float = 0.4f, angle: Float = 0f): ShaderBrush {
        val c = cell.roundToInt().coerceAtLeast(3)
        val key = "L${color.toArgb()}/$c/${(weight * 100).roundToInt()}/${angle.roundToInt()}"
        return cache.getOrPut(key) {
            val bmp = Bitmap.createBitmap(c, c, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawRect(0f, 0f, c.toFloat(), c * weight, Paint().apply { this.color = color.toArgb() })
            ShaderBrush(BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(Matrix().apply { setRotate(angle) }) })
        }
    }
}

/** [path] in solid [ink]; [over] multiplies it over whatever is printed already. */
fun DrawScope.solid(path: Path, ink: Color, over: Boolean = false, alpha: Float = 1f) =
    drawPath(path, ink, alpha = alpha, blendMode = if (over) BlendMode.Multiply else BlendMode.SrcOver)

/** [path] as a dot screen of [ink]. */
fun DrawScope.screen(path: Path, ink: Color, cell: Float, fill: Float = 0.72f, angle: Float = 15f, over: Boolean = true) =
    drawPath(path, Riso.dots(ink, cell, fill, angle), blendMode = if (over) BlendMode.Multiply else BlendMode.SrcOver)

/** Draws [block] shifted by [by]: the second pass landing a little off register. */
inline fun DrawScope.offRegister(by: Offset, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(by.x, by.y)
    block()
    drawContext.transform.translate(-by.x, -by.y)
}
