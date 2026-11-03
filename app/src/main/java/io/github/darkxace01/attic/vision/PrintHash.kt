package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * A 64-bit fingerprint of what a print shows, the same for two scans of the same print (different light,
 * exposure, a slightly different crop, enhanced or not) and far apart for different prints. A perceptual
 * hash: the print's broad shapes (the lowest frequencies of its picture), each bit saying whether one of
 * them is stronger than the typical one. Brightness, contrast, noise and fine detail barely move them.
 */
object PrintHash {
    /**
     * At most this many differing bits: the same print. In tests, rescans of a print (other light, crop,
     * noise, enhanced) differ in up to 10 bits and different prints in 24 or more; about 32 is typical.
     */
    const val SAME = 14

    fun of(rgba: Mat): Long {
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, if (rgba.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
        // Leave out the outer edge: crops of the same print differ most there (borders, a sliver of page).
        val inset = gray.submat((gray.rows() * 0.06).toInt(), (gray.rows() * 0.94).toInt(), (gray.cols() * 0.06).toInt(), (gray.cols() * 0.94).toInt())
        val small = Mat()
        Imgproc.resize(inset, small, Size(32.0, 32.0), 0.0, 0.0, Imgproc.INTER_AREA)
        small.convertTo(small, CvType.CV_32F)
        val dct = Mat()
        Core.dct(small, dct)
        val v = FloatArray(32 * 32).also { dct.get(0, 0, it) }
        listOf(gray, inset, small, dct).forEach { it.release() }
        // The 8 x 8 lowest frequencies, without the overall brightness (0, 0).
        val low = FloatArray(64) { k -> v[(k / 8) * 32 + k % 8] }
        val median = low.drop(1).sorted()[31]
        var h = 0L
        for (k in 0 until 64) h = (h shl 1) or (if (k > 0 && low[k] > median) 1L else 0L)
        return h
    }

    /** The fingerprints of [rgba] turned 0, 90, 180 and 270 degrees, for prints scanned the other way up. */
    fun allTurns(rgba: Mat): List<Long> {
        val out = mutableListOf(of(rgba))
        val turned = Mat()
        for (r in listOf(Core.ROTATE_90_CLOCKWISE, Core.ROTATE_180, Core.ROTATE_90_COUNTERCLOCKWISE)) {
            Core.rotate(rgba, turned, r)
            out += of(turned)
        }
        turned.release()
        return out
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    fun same(a: Long, b: Long) = distance(a, b) <= SAME

    /**
     * Whether a page now in view is a different page from the one captured: most of its prints match none
     * of the captured ones. A hand or a shadow over one print doesn't make a new page.
     */
    fun isNewPage(now: List<Long>, captured: List<Long>): Boolean {
        if (now.isEmpty()) return false
        if (captured.isEmpty()) return true
        val unmatched = now.count { h -> captured.none { same(it, h) } }
        return unmatched * 2 > now.size
    }
}
