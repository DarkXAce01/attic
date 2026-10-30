package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Undoes the fading of old prints: 80s and 90s colour prints drift orange or magenta and lose their
 * blacks. Each colour channel is stretched so its darkest and brightest 0.5% reach black and white,
 * which removes the colour cast and restores contrast in one step. The stretch is capped so a photo
 * that's meant to be dark or warm (a sunset, a candlelit room) isn't pushed to grey.
 */
object ColorFix {
    private const val CLIP = 0.005
    private const val MAX_GAIN = 2.2

    /** Fixes an RGBA image; alpha is kept. */
    fun apply(rgba: Mat): Mat = stretch(rgba)

    /** Fixes an RGB image. */
    fun applyRgb(rgb: Mat): Mat = stretch(rgb)

    private fun stretch(img: Mat): Mat {
        val n = img.channels()
        val small = Mat()
        val scale = 800.0 / max(img.cols(), img.rows())
        if (scale < 1) org.opencv.imgproc.Imgproc.resize(img, small, org.opencv.core.Size(), scale, scale) else img.copyTo(small)

        val channels = ArrayList<Mat>()
        Core.split(small, channels)
        val lut = Mat(1, 256, CvType.makeType(CvType.CV_8U, n))
        val table = ByteArray(256 * n)
        for (c in 0 until n) {
            val isAlpha = c == 3
            val (lo, hi) = if (isAlpha) 0 to 255 else percentiles(channels[c])
            val range = max(1, hi - lo)
            // Cap the gain; centre whatever stretch is left so midtones stay where they were.
            val gain = min(255.0 / range, MAX_GAIN)
            val offset = if (255.0 / range > MAX_GAIN) (lo + hi) / 2.0 - 127.5 / gain else lo.toDouble()
            for (v in 0 until 256) {
                val out = if (isAlpha) v else ((v - offset) * gain).roundToInt().coerceIn(0, 255)
                table[v * n + c] = out.toByte()
            }
        }
        lut.put(0, 0, table)
        val out = Mat()
        Core.LUT(img, lut, out)
        (channels + listOf(small, lut)).forEach { it.release() }
        return out
    }

    private fun percentiles(channel: Mat): Pair<Int, Int> {
        val hist = Mat()
        org.opencv.imgproc.Imgproc.calcHist(listOf(channel), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        val counts = FloatArray(256).also { hist.get(0, 0, it) }
        hist.release()
        val total = counts.sum()
        var acc = 0f
        var lo = 0
        while (lo < 255 && acc + counts[lo] < total * CLIP) { acc += counts[lo]; lo++ }
        acc = 0f
        var hi = 255
        while (hi > 0 && acc + counts[hi] < total * CLIP) { acc += counts[hi]; hi-- }
        return lo to max(hi, lo + 1)
    }
}
