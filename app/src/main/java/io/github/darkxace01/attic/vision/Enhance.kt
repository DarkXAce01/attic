package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Makes a photographed print look like the print, not a photo of it. Classic darkroom-style steps only,
 * nothing invented: every change is a smooth function of pixels that are already there.
 *
 *  1. (Strong only) a light edge-preserving smoothing, so film grain and phone noise aren't sharpened.
 *  2. Levels per channel ([ColorFix]): fading and colour casts out, blacks back.
 *  3. Local contrast (CLAHE on lightness), blended back in so faces don't go crunchy.
 *  4. Sharpening on lightness only, and only where there's real detail, so flat sky and skin stay smooth
 *     and edges don't get colour fringes.
 *  5. A little colour back, the way faded prints lose it.
 */
object Enhance {
    const val OFF = 0
    const val NATURAL = 1
    const val STRONG = 2

    private class Settings(
        val denoise: Boolean,
        val claheClip: Double,
        val claheBlend: Double,
        val sharpenAmount: Double,
        val chroma: Double,
    )

    private fun settings(level: Int) = when (level) {
        STRONG -> Settings(denoise = true, claheClip = 2.2, claheBlend = 0.8, sharpenAmount = 1.0, chroma = 1.15)
        else -> Settings(denoise = false, claheClip = 1.6, claheBlend = 0.55, sharpenAmount = 0.6, chroma = 1.07)
    }

    /** Returns a new RGBA Mat; [rgba] is left untouched. [level] [OFF] returns a plain copy. */
    fun apply(rgba: Mat, level: Int): Mat {
        if (level == OFF) return rgba.clone()
        val s = settings(level)

        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        if (s.denoise) {
            val smooth = Mat()
            Imgproc.bilateralFilter(rgb, smooth, 5, 14.0, 3.0)
            rgb.release()
            smooth.copyTo(rgb)
            smooth.release()
        }

        val leveled = ColorFix.applyRgb(rgb)
        rgb.release()

        val lab = Mat()
        Imgproc.cvtColor(leveled, lab, Imgproc.COLOR_RGB2Lab)
        leveled.release()
        val ch = ArrayList<Mat>()
        Core.split(lab, ch)
        lab.release()
        val (l, a, b) = Triple(ch[0], ch[1], ch[2])

        localContrast(l, s.claheClip, s.claheBlend)

        sharpen(l, s.sharpenAmount)

        val chromaLut = Mat(1, 256, CvType.CV_8UC1).apply {
            put(0, 0, ByteArray(256) { v -> (128 + (v - 128) * s.chroma).roundToInt().coerceIn(0, 255).toByte() })
        }
        Core.LUT(a, chromaLut, a)
        Core.LUT(b, chromaLut, b)
        chromaLut.release()

        val merged = Mat()
        Core.merge(ch, merged)
        ch.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(merged, out, Imgproc.COLOR_Lab2RGB)
        merged.release()
        Imgproc.cvtColor(out, out, Imgproc.COLOR_RGB2RGBA)
        return out
    }

    /**
     * CLAHE on its own turns grain in flat areas into texture. So it runs on a smoothed base layer, and only
     * the change it makes to the base is added back: tones get local contrast, grain passes through as is.
     */
    private fun localContrast(l: Mat, clip: Double, strength: Double) {
        val base = Mat()
        Imgproc.GaussianBlur(l, base, Size(0.0, 0.0), max(2.0, max(l.cols(), l.rows()) / 800.0))
        val toned = Mat()
        Imgproc.createCLAHE(clip, Size(8.0, 8.0)).apply(base, toned)
        val fl = Mat(); val fBase = Mat(); val fToned = Mat()
        l.convertTo(fl, CvType.CV_32F)
        base.convertTo(fBase, CvType.CV_32F)
        toned.convertTo(fToned, CvType.CV_32F)
        Core.subtract(fToned, fBase, fToned)
        Core.scaleAdd(fToned, strength, fl, fl)
        fl.convertTo(l, CvType.CV_8U)
        listOf(base, toned, fl, fBase, fToned).forEach { it.release() }
    }

    /** Unsharp mask on lightness, skipping pixels whose local detail is below the noise threshold. */
    private fun sharpen(l: Mat, amount: Double) {
        val sigma = max(1.0, max(l.cols(), l.rows()) / 1600.0)
        val blur = Mat()
        Imgproc.GaussianBlur(l, blur, Size(0.0, 0.0), sigma)
        val sharp = Mat()
        Core.addWeighted(l, 1 + amount, blur, -amount, 0.0, sharp)
        val detail = Mat()
        Core.absdiff(l, blur, detail)
        val mask = Mat()
        Imgproc.threshold(detail, mask, 3.0, 255.0, Imgproc.THRESH_BINARY)
        // Soften the mask's edges so sharpened and untouched areas blend without a seam.
        Imgproc.GaussianBlur(mask, mask, Size(0.0, 0.0), sigma)
        blend(sharp, l, mask, l)
        listOf(blur, sharp, detail, mask).forEach { it.release() }
    }

    /** out = a·m + b·(1-m), with m an 8-bit mask. */
    private fun blend(a: Mat, b: Mat, mask: Mat, out: Mat) {
        val fa = Mat(); val fb = Mat(); val fm = Mat()
        a.convertTo(fa, CvType.CV_32F)
        b.convertTo(fb, CvType.CV_32F)
        mask.convertTo(fm, CvType.CV_32F, 1 / 255.0)
        Core.subtract(fa, fb, fa)
        Core.multiply(fa, fm, fa)
        Core.add(fa, fb, fa)
        fa.convertTo(out, CvType.CV_8U)
        listOf(fa, fb, fm).forEach { it.release() }
    }

    fun label(level: Int) = when (level) {
        OFF -> "Original"
        STRONG -> "Strong"
        else -> "Natural"
    }
}
