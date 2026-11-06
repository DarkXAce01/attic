package io.github.darkxace01.attic.vision

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Making a small print bigger: Real-ESRGAN's general model (BSD-3, by Xintao Wang) draws in the detail a
 * plain stretch would blur, four times over. It works through the print in small overlapping tiles so it
 * fits in a phone's memory, and each tile is brought down to the size wanted on the way out.
 */
class Upscale(model: ByteArray) {
    private val net: Net = Dnn.readNetFromONNX(MatOfByte(*model))

    /**
     * [rgba] made [factor] times bigger (up to 4). [progress] gets tiles done and how many. Not
     * thread-safe: one at a time.
     */
    fun run(rgba: Mat, factor: Double, progress: (Int, Int) -> Unit = { _, _ -> }): Mat {
        val f = factor.coerceIn(1.0, 4.0)
        val w = rgba.cols(); val h = rgba.rows()
        val outW = (w * f).roundToInt(); val outH = (h * f).roundToInt()
        val rgb = Mat().also { Imgproc.cvtColor(rgba, it, if (rgba.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_BGR2RGB) }
        // Edges padded once so every tile, the ones at the edge too, is the same size.
        val padded = Mat().also { Core.copyMakeBorder(rgb, it, PAD, PAD + TILE, PAD, PAD + TILE, Core.BORDER_REFLECT) }
        rgb.release()
        val out = Mat(outH, outW, CvType.CV_8UC4, Scalar(0.0, 0.0, 0.0, 255.0))
        val cols = ceil(w / TILE.toDouble()).toInt(); val rows = ceil(h / TILE.toDouble()).toInt()
        val total = cols * rows
        var done = 0
        val big = mutableListOf<Mat>()
        for (ty in 0 until rows) for (tx in 0 until cols) {
            val x0 = tx * TILE; val y0 = ty * TILE
            val tile = padded.submat(Rect(x0, y0, TILE + 2 * PAD, TILE + 2 * PAD))
            val blob = Dnn.blobFromImage(tile, 1.0 / 255, Size(), Scalar(0.0, 0.0, 0.0), false, false, CvType.CV_32F)
            net.setInput(blob)
            val res = net.forward()
            big.clear()
            Dnn.imagesFromBlob(res, big)
            val up = big[0]
            // The tile without its overlap, in the 4x picture.
            val inner = up.submat(Rect(PAD * 4, PAD * 4, TILE * 4, TILE * 4))
            // Where it goes in the output, and at what size.
            val ox0 = (x0 * f).roundToInt(); val oy0 = (y0 * f).roundToInt()
            val ox1 = min(outW, ((x0 + TILE) * f).roundToInt()); val oy1 = min(outH, ((y0 + TILE) * f).roundToInt())
            val fullW = ((x0 + TILE) * f).roundToInt() - ox0; val fullH = ((y0 + TILE) * f).roundToInt() - oy0
            val sized = Mat()
            Imgproc.resize(inner, sized, Size(fullW.toDouble(), fullH.toDouble()), 0.0, 0.0, if (f < 4.0) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            val bytes = Mat(); sized.convertTo(bytes, CvType.CV_8U, 255.0)
            val withAlpha = Mat(); Imgproc.cvtColor(bytes, withAlpha, Imgproc.COLOR_RGB2RGBA)
            if (ox1 > ox0 && oy1 > oy0) withAlpha.submat(Rect(0, 0, ox1 - ox0, oy1 - oy0)).copyTo(out.submat(Rect(ox0, oy0, ox1 - ox0, oy1 - oy0)))
            listOf(blob, res, up, inner, sized, bytes, withAlpha, tile).forEach { it.release() }
            progress(++done, total)
        }
        padded.release()
        return out
    }

    companion object {
        /** Input pixels per tile side, and the overlap each side so tiles meet without a seam. */
        const val TILE = 128
        const val PAD = 10

        /** The biggest print Attic makes, on its long side. */
        const val MAX_SIDE = 4800

        /** How much bigger a print of this size can usefully be made; below 1.5 it isn't worth it. */
        fun factorFor(width: Int, height: Int): Double = min(4.0, MAX_SIDE.toDouble() / max(width, height))
    }
}
