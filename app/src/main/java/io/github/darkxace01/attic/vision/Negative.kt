package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.ln
import kotlin.math.max

/**
 * Turns a photographed film negative (on a lightbox) into the picture. Colour negative film is orange
 * all over (the mask) and records light as density, so for each colour separately: divide by the film's
 * own colour (its clearest parts), turn what's left into density, stretch each channel's density to the
 * full range (which takes the orange out and balances the colour), then back to light. Black-and-white
 * negatives come out the same way. Slides are positive already and need none of this.
 */
object Negative {
    fun develop(rgba: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, if (rgba.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB)
        val f = Mat()
        rgb.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0)
        rgb.release()
        val chans = ArrayList<Mat>()
        Core.split(f, chans)
        f.release()
        val w = chans[0].cols(); val h = chans[0].rows()
        // Each colour as density, relative to clear film (the film's own colour: its clearest few pixels).
        val dens = chans.map { c ->
            val v = FloatArray(w * h).also { c.get(0, 0, it) }
            c.release()
            val base = percentile(v, 0.998).coerceAtLeast(1e-3f)
            for (i in v.indices) v[i] = -log10((v[i] / base).coerceIn(1e-3f, 1f))
            v
        }
        // Each colour's thinnest and densest parts (ignoring stray specks) span black to white. That takes
        // the film's colour out; a picture with no true black or white in it (haze, a sunset) can come out
        // more neutral than it was, which the look's colour fix and the Original/Natural choice can settle.
        val lows = dens.map { percentile(it, 0.002) }
        val spans = dens.mapIndexed { i, v -> max(1e-3f, percentile(v, 0.998) - lows[i]) }
        val out = dens.mapIndexed { i, v ->
            val span = spans[i]
            for (k in v.indices) {
                val q = ((v[k] - lows[i]) / span).coerceIn(0f, 1f)
                // Film answers light on a log scale; this undoes it.
                v[k] = (Math.pow(10.0, q.toDouble()).toFloat() - 1f) / 9f
            }
            Mat(h, w, CvType.CV_32F).also { it.put(0, 0, v) }
        }
        val merged = Mat()
        Core.merge(out, merged)
        out.forEach { it.release() }
        val u8 = Mat()
        merged.convertTo(u8, CvType.CV_8UC3, 255.0)
        merged.release()
        val result = Mat()
        Imgproc.cvtColor(u8, result, Imgproc.COLOR_RGB2RGBA)
        u8.release()
        return result
    }

    private fun log10(x: Float) = (ln(x.toDouble()) / ln(10.0)).toFloat()

    /** The value [q] of the way up [v] (sampled, so big frames stay quick). */
    private fun percentile(v: FloatArray, q: Double): Float {
        val step = max(1, v.size / 200_000)
        val s = FloatArray((v.size + step - 1) / step) { v[it * step] }
        s.sort()
        return s[((s.size - 1) * q).toInt()]
    }
}
