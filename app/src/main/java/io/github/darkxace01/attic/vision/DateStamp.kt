package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import java.time.LocalDate
import kotlin.math.min

/**
 * Reads the date a film camera printed in the corner of a photo ('87 7 14 in glowing orange digits).
 *
 * A small network trained on synthetic stamps (ml/stamp.py) reads one strip at a time: the print's
 * bottom-right corner, 0.2 x 0.8 of its short side. The stamp sits there on the camera's landscape frame,
 * so each of the print's four turns is tried, and the clearest reading that is a real date wins.
 */
class DateStamp(model: ByteArray) {
    private val net: Net = Dnn.readNetFromONNX(MatOfByte(*model))

    /** What the stamp says, how sure the reader is (0..1, its least certain character) and the dates it can mean. */
    data class Reading(val text: String, val confidence: Float, val dates: List<LocalDate>)

    /** The stamp on [print] (RGBA or RGB, upright or not), or null if it has none. Thread-safe. */
    fun read(print: Mat): Reading? {
        var best: Reading? = null
        val turned = Mat()
        for (turn in listOf(-1, Core.ROTATE_90_CLOCKWISE, Core.ROTATE_180, Core.ROTATE_90_COUNTERCLOCKWISE)) {
            val img = if (turn < 0) print else turned.also { Core.rotate(print, it, turn) }
            val (text, confidence) = readStrip(img)
            val dates = StampDate.read(text)
            if (dates.isNotEmpty() && confidence >= MIN_CONFIDENCE && (best == null || confidence > best.confidence)) {
                best = Reading(text, confidence, dates)
            }
        }
        turned.release()
        return best
    }

    /** The text in [img]'s bottom-right strip and its confidence. */
    internal fun readStrip(img: Mat): Pair<String, Float> {
        val s = min(img.rows(), img.cols())
        val sh = (s * STRIP_TALL).toInt()
        val sw = (s * STRIP_WIDE).toInt()
        val cut = img.submat(img.rows() - sh, img.rows(), img.cols() - sw, img.cols())
        val small = Mat()
        Imgproc.resize(cut, small, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        cut.release()
        // The network reads RGB, as it was trained.
        val rgb = if (small.channels() == 4) Mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_RGBA2RGB); small.release() } else small
        val blob = Dnn.blobFromImage(rgb, 1.0, Size(W.toDouble(), H.toDouble()), Scalar(0.0, 0.0, 0.0), false, false)
        rgb.release()
        val probs = FloatArray(CLASSES * STEPS)
        synchronized(net) {
            net.setInput(blob)
            val out = net.forward()
            out.reshape(1, 1).get(0, 0, probs)
            out.release()
        }
        blob.release()
        return decode(probs)
    }

    companion object {
        const val W = 192
        const val H = 48
        const val STEPS = 48
        const val ALPHABET = "0123456789' "
        const val CLASSES = ALPHABET.length + 1
        const val STRIP_TALL = 0.2
        const val STRIP_WIDE = 0.8

        /** Below this, a reading is a guess: an orange sign or a sunset read as digits. */
        const val MIN_CONFIDENCE = 0.6f

        /**
         * Greedy CTC over [probs] (CLASSES x STEPS, class-major as the network gives it): the likeliest
         * class at each step, repeats merged, blanks (class 0) dropped. Confidence: the least certain
         * character's probability, 0 with no characters.
         */
        fun decode(probs: FloatArray): Pair<String, Float> {
            val sb = StringBuilder()
            var prev = 0
            var least = 1f
            var peak = 0f
            for (t in 0 until STEPS) {
                var c = 0
                var p = probs[t]
                for (k in 1 until CLASSES) {
                    val v = probs[k * STEPS + t]
                    if (v > p) { p = v; c = k }
                }
                if (c != prev) {
                    // The character just finished counts with its most certain step.
                    if (prev != 0) least = min(least, peak)
                    peak = 0f
                    if (c != 0) sb.append(ALPHABET[c - 1])
                }
                if (c != 0) peak = maxOf(peak, p)
                prev = c
            }
            if (prev != 0) least = min(least, peak)
            val text = sb.toString().trim().replace(Regex("\\s+"), " ")
            return text to (if (text.isEmpty()) 0f else least)
        }
    }
}
