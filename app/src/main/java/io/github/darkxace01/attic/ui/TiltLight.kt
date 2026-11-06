package io.github.darkxace01.attic.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Tilt light: a soft reflection that slides over a glossy print as the phone tilts, the way light moves
 * over a real one held in the hand. Only light on top: the photo underneath is never changed, and with
 * the switch off (Settings) nothing is drawn and the sensor isn't used.
 */
object TiltLight {
    /** How far the phone leans from where it's being held, each side -1..1 (about 25 degrees is all the way). */
    @Composable
    fun rememberTilt(on: Boolean): State<Offset> {
        val context = LocalContext.current
        val tilt = remember { mutableStateOf(Offset.Zero) }
        DisposableEffect(on) {
            if (!on) { tilt.value = Offset.Zero; return@DisposableEffect onDispose { } }
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            var rest: FloatArray? = null
            val smooth = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    for (i in 0..2) smooth[i] += (e.values[i] - smooth[i]) * 0.15f
                    // Where it's held is "level"; that slowly follows the hand, so lying in bed works too.
                    val r = rest ?: smooth.copyOf().also { rest = it }
                    for (i in 0..2) r[i] += (smooth[i] - r[i]) * 0.004f
                    tilt.value = lean(smooth[0] - r[0], smooth[1] - r[1])
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sm.unregisterListener(listener) }
        }
        return tilt
    }

    /** Gravity's change along the screen's x and y (m/s²) as a lean, -1..1 each way. */
    fun lean(dx: Float, dy: Float): Offset = Offset((-dx / FULL).coerceIn(-1f, 1f), (dy / FULL).coerceIn(-1f, 1f))

    /** About 25 degrees of lean, in m/s² of gravity across the screen. */
    private const val FULL = 4.1f

    /** Where the band of light crosses a print for a lean: 0 at its top-left corner, 1 at its bottom right. */
    fun bandAt(tilt: Offset): Float = 0.5f + (tilt.x + tilt.y) / 2f * 0.9f

    /** How bright the band is: nothing while the print is held level, so a still print looks like a print. */
    fun strength(tilt: Offset): Float = (tilt.getDistance() * 2.5f).coerceAtMost(1f)
}

/** Draws the moving reflection over whatever this is (a print), when [tilt] isn't null. */
fun Modifier.tiltLight(tilt: State<Offset>?): Modifier = if (tilt == null) this else drawWithContent {
    drawContent()
    val t = TiltLight.bandAt(tilt.value)
    val diag = size.width + size.height
    val c = Offset(size.width * t, size.height * t)
    val half = diag * 0.18f
    val dir = Offset(size.width, size.height) / Offset(size.width, size.height).getDistance()
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.34f * TiltLight.strength(tilt.value)), 1f to Color.Transparent,
            start = c - dir * half, end = c + dir * half,
        ),
        blendMode = BlendMode.Screen,
    )
}
